#!/bin/bash
set -e

echo "🔧 部署独立手机访问的音频转MIDI服务..."

# 1. 安装依赖
sudo apt update
sudo DEBIAN_FRONTEND=noninteractive apt install -y --no-install-recommends \
    python3.10-venv \
    python3-pip \
    ffmpeg \
    libsndfile1 \
    libopenblas-dev \
    nginx \
    supervisor \
    certbot \
    python3-certbot-nginx

# 2. 创建应用目录
sudo mkdir -p /transcribe
sudo chown -R $USER:$USER /transcribe
cd /transcribe

# 3. 创建虚拟环境
python3 -m venv venv
source venv/bin/activate

# 4. 安装Python包
cat > requirements.txt <<EOF
flask==2.3.3
gunicorn==21.2.0
librosa==0.11.0
soundfile==0.13.1
noisereduce==3.0.3
numpy==1.26.4
werkzeug==3.1.5
onnxruntime==1.15.1
basic-pitch[onnx]==0.4.0
python-dotenv==1.0.0
EOF

pip install --no-cache-dir -r requirements.txt

# 5. 生成随机API密钥
API_KEY=$(openssl rand -hex 32)
echo "🔐 生成API密钥: $API_KEY"
echo "APP_API_KEY=$API_KEY" > .env

# 6. 部署应用代码 (带API密钥认证)
cat > app.py <<'PYCODE'
import os
import sys
import uuid
import time
import threading
import traceback
import subprocess
import dotenv
from flask import Flask, request, jsonify, send_from_directory
from werkzeug.utils import secure_filename
import librosa
import soundfile as sf
import noisereduce as nr
import numpy as np
import shutil
from pathlib import Path
import json
from datetime import datetime, timedelta

# ---------------------- 全局配置 ----------------------
app = Flask(__name__)
BASE_DIR = "/transcribe"
os.makedirs(os.path.join(BASE_DIR, 'uploads'), exist_ok=True)
os.makedirs(os.path.join(BASE_DIR, 'results'), exist_ok=True)
os.makedirs(os.path.join(BASE_DIR, 'temp'), exist_ok=True)
os.makedirs(os.path.join(BASE_DIR, 'tasks'), exist_ok=True)

# 加载环境变量
dotenv.load_dotenv()

# 从环境变量获取API密钥
API_KEY = os.environ.get('APP_API_KEY', 'default_api_key_for_development')
ALERT_THRESHOLD_GB = 3

app.config['UPLOAD_FOLDER'] = os.path.join(BASE_DIR, 'uploads')
app.config['RESULT_FOLDER'] = os.path.join(BASE_DIR, 'results')
app.config['MAX_CONTENT_LENGTH'] = 100 * 1024 * 1024  # 100MB

ALLOWED_EXTENSIONS = {'wav', 'mp3', 'flac', 'ogg', 'm4a'}

# 任务管理器
class TaskManager:
    def __init__(self, storage_dir="/transcribe/tasks"):
        self.storage_dir = Path(storage_dir)
        self.storage_dir.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()
        self._start_cleanup_thread()

    def _start_cleanup_thread(self):
        """启动后台清理线程"""
        def cleanup_loop():
            while True:
                self._cleanup_old_tasks()
                time.sleep(3600)  # 每小时清理一次

        threading.Thread(target=cleanup_loop, daemon=True).start()

    def _get_task_file(self, task_id):
        """获取任务文件路径"""
        return self.storage_dir / f"{task_id}.json"

    def create_task(self, task_id, filename):
        """创建新任务"""
        task_data = {
            "task_id": task_id,
            "filename": filename,
            "status": "queued",
            "created_at": datetime.now().isoformat(),
            "updated_at": datetime.now().isoformat(),
            "position_in_queue": 0,
            "error": None,
            "result_path": None,
            "processing_time": None
        }

        with self.lock:
            with open(self._get_task_file(task_id), 'w') as f:
                json.dump(task_data, f, indent=2)

        return task_data

    def update_task(self, task_id, **kwargs):
        """更新任务状态"""
        task_file = self._get_task_file(task_id)
        if not task_file.exists():
            return self.create_task(task_id, kwargs.get('filename', 'unknown'))

        with self.lock:
            with open(task_file, 'r') as f:
                task_data = json.load(f)

            # 更新字段
            task_data.update(kwargs)
            task_data["updated_at"] = datetime.now().isoformat()

            with open(task_file, 'w') as f:
                json.dump(task_data, f, indent=2)

        return task_data

    def get_task(self, task_id):
        """获取任务状态"""
        task_file = self._get_task_file(task_id)
        if not task_file.exists():
            return None

        try:
            with open(task_file, 'r') as f:
                return json.load(f)
        except (json.JSONDecodeError, IOError):
            return None

    def _cleanup_old_tasks(self):
        """清理过期任务"""
        now = datetime.now()
        with self.lock:
            for task_file in self.storage_dir.glob("*.json"):
                try:
                    with open(task_file, 'r') as f:
                        task_data = json.load(f)

                    updated_at = datetime.fromisoformat(task_data.get("updated_at", ""))
                    if now - updated_at > timedelta(hours=24):  # 24小时过期
                        task_file.unlink()
                except Exception as e:
                    print(f"清理任务失败 {task_file}: {str(e)}")

    def get_queue_position(self, task_id):
        """获取任务在队列中的位置"""
        # 简单实现：统计状态为 'queued' 或 'processing' 的任务数
        count = 0
        for task_file in self.storage_dir.glob("*.json"):
            try:
                with open(task_file, 'r') as f:
                    task_data = json.load(f)
                if task_data.get("task_id") == task_id:
                    return count
                if task_data.get("status") in ["queued", "processing"]:
                    count += 1
            except:
                continue
        return count

# 全局任务管理器实例
task_manager = TaskManager()

def require_api_key(f):
    from functools import wraps
    @wraps(f)
    def decorated(*args, **kwargs):
        api_key = request.headers.get('X-API-Key')
        if not api_key or api_key != API_KEY:
            return jsonify({"error": "无效的API密钥"}), 401
        return f(*args, **kwargs)
    return decorated

def check_disk_space():
    """检查可用磁盘空间 (GB)"""
    stat = os.statvfs(BASE_DIR)
    free_bytes = stat.f_bfree * stat.f_frsize
    return free_bytes / (1024 ** 3)

def allowed_file(filename):
    return '.' in filename and filename.rsplit('.', 1)[1].lower() in ALLOWED_EXTENSIONS

def safe_remove(path):
    """安全删除文件/目录"""
    try:
        if os.path.isdir(path):
            subprocess.run(['rm', '-rf', path], check=False)
        elif os.path.exists(path):
            os.remove(path)
    except Exception as e:
        print(f"清理错误 {path}: {str(e)}")

def convert_to_wav(input_path, output_path, target_sr=22050):
    """内存优化：转换为22050Hz单声道WAV"""
    cmd = [
        'ffmpeg', '-i', input_path,
        '-ar', str(target_sr),
        '-ac', '1',  # 强制单声道
        '-b:a', '64k',  # 降低比特率
        '-f', 'wav',
        '-y', output_path
    ]
    try:
        subprocess.run(cmd, check=True, stderr=subprocess.DEVNULL)
        return output_path
    except subprocess.CalledProcessError as e:
        raise Exception(f"音频转换失败: {str(e)}")

def audio_noise_reduce(input_audio, output_audio, sr=22050):
    """超低内存降噪处理"""
    try:
        # 分块读取避免大文件内存溢出
        y, _ = librosa.load(input_audio, sr=sr, mono=True, duration=120)  # 限制120秒

        # 检查文件是否太短
        if len(y) < sr * 0.5:  # 少于0.5秒
            # 直接复制文件，不进行降噪
            shutil.copy2(input_audio, output_audio)
            print("音频太短，跳过降噪处理")
            return output_audio

        # 取前0.2秒作为噪声样本
        noise_len = min(int(0.2 * sr), len(y)//2)
        noise_sample = y[:noise_len]

        # 确保噪声样本有足够的长度
        if len(noise_sample) < 1024:
            noise_sample = np.tile(noise_sample, (1024 // len(noise_sample) + 1))[:1024]

        # 低强度降噪参数
        y_denoised = nr.reduce_noise(
            y=y,
            sr=sr,
            y_noise=noise_sample,
            prop_decrease=0.6,  # 降低降噪强度
            n_fft=1024,         # 减小FFT尺寸
            hop_length=512,
            use_tqdm=False,
            n_jobs=1            # 禁用多线程
        )

        # 保存降噪后的音频
        sf.write(output_audio, y_denoised, sr)
        return output_audio

    except Exception as e:
        print(f"降噪失败: {str(e)}")
        # 降级策略: 直接复制原始文件
        try:
            shutil.copy2(input_audio, output_audio)
            print("降级: 使用原始音频文件")
            return output_audio
        except Exception as copy_error:
            print(f"降级复制也失败: {str(copy_error)}")
            raise Exception(f"降噪失败且无法降级: {str(e)}")

def process_audio(task_id, input_path, original_filename):
    """后台处理任务（带资源保护）"""
    start_time = time.time()
    temp_dir = os.path.join(BASE_DIR, 'temp', task_id)
    os.makedirs(temp_dir, exist_ok=True)
    wav_path = os.path.join(temp_dir, "converted.wav")
    denoised_path = os.path.join(temp_dir, "denoised.wav")
    midi_dir = os.path.join(temp_dir, "midi")

    try:
        task_manager.update_task(task_id, status='converting')
        convert_to_wav(input_path, wav_path, target_sr=22050)

        # 检查转换后大小
        if os.path.getsize(wav_path) > 100 * 1024 * 1024:  # 100MB
            raise Exception("转换后文件过大，请降低音质或缩短时长")

        task_manager.update_task(task_id, status='denoising')
        audio_noise_reduce(wav_path, denoised_path, sr=22050)

        task_manager.update_task(task_id, status='transcribing')
        os.makedirs(midi_dir, exist_ok=True)

        subprocess.run([
            'basic-pitch',
            midi_dir,
            denoised_path,
            '--save-midi'
        ], check=True, env={**os.environ, "BASIC_PITCH_BACKEND": "onnxruntime"})

        # 获取MIDI文件
        midi_file = [f for f in os.listdir(midi_dir) if f.endswith('.mid')][0]
        result_path = os.path.join(app.config['RESULT_FOLDER'], f"{task_id}.mid")
        os.rename(os.path.join(midi_dir, midi_file), result_path)

        task_manager.update_task(task_id,
            status='completed',
            result_path=result_path,
            processing_time=round(time.time() - start_time, 2),
            download_url=f"/download/{os.path.basename(result_path)}"
        )

    except Exception as e:
        error_msg = str(e)
        if "MemoryError" in error_msg or "Killed" in error_msg:
            error_msg = "内存不足：请尝试更短的音频（建议<60秒）"
        task_manager.update_task(task_id,
            status='failed',
            error=error_msg
        )
    finally:
        # 释放资源
        safe_remove(temp_dir)
        safe_remove(input_path)

@app.route('/upload', methods=['POST'])
@require_api_key
def upload_audio():
    # 检查磁盘空间
    free_gb = check_disk_space()
    if free_gb < ALERT_THRESHOLD_GB:
        return jsonify({"error": f"服务器空间不足({free_gb:.1f}GB)，请稍后再试"}), 507

    # 验证文件
    if 'file' not in request.files:
        return jsonify({"error": "未提供音频文件"}), 400

    file = request.files['file']
    if file.filename == '':
        return jsonify({"error": "空文件名"}), 400

    if not allowed_file(file.filename):
        return jsonify({"error": f"不支持的格式. 支持: {', '.join(ALLOWED_EXTENSIONS)}"}), 400

    # 生成任务ID
    task_id = str(uuid.uuid4())
    task_manager.create_task(task_id, file.filename)

    # 保存文件
    filename = secure_filename(file.filename)
    input_path = os.path.join(app.config['UPLOAD_FOLDER'], f"{task_id}_{filename}")
    file.save(input_path)

    # 启动后台线程
    threading.Thread(
        target=process_audio,
        args=(task_id, input_path, filename),
        daemon=True
    ).start()

    return jsonify({
        "task_id": task_id,
        "status": "queued",
        "position_in_queue": task_manager.get_queue_position(task_id),
        "message": "处理中，用GET /task/<id>查询状态"
    }), 202

@app.route('/task/<task_id>', methods=['GET'])
@require_api_key
def get_task_status(task_id):
    task = task_manager.get_task(task_id)
    if not task:
        return jsonify({"error": "任务不存在"}), 404

    safe_task = task.copy()
    safe_task.pop('result_path', None)  # 移除敏感信息

    return jsonify(safe_task)

@app.route('/download/<filename>', methods=['GET'])
@require_api_key
def download_result(filename):
    safe_filename = secure_filename(filename)
    file_path = os.path.join(app.config['RESULT_FOLDER'], safe_filename)

    if not os.path.exists(file_path):
        return jsonify({"error": "文件不存在或已过期"}), 404

    return send_from_directory(
        app.config['RESULT_FOLDER'],
        safe_filename,
        as_attachment=True,
        download_name=safe_filename
    )

@app.route('/health', methods=['GET'])
def health_check():
    try:
        # 检查磁盘空间
        free_space = check_disk_space()

        # 检查关键依赖
        dependencies = {
            "librosa": librosa.__version__,
            "noisereduce": nr.__version__,
            "basic_pitch": "0.4.0"  # 固定版本
        }

        # 检查任务目录
        task_dir = Path("/transcribe/tasks")
        task_count = len(list(task_dir.glob("*.json"))) if task_dir.exists() else 0

        return jsonify({
            "status": "healthy",
            "free_disk_gb": round(free_space, 2),
            "active_tasks": len([t for t in os.listdir("/transcribe/temp")
                               if os.path.isdir(os.path.join("/transcribe/temp", t))]),
            "task_count": task_count,
            "dependencies": dependencies,
            "memory_used_percent": int(subprocess.getoutput("free | awk '/Mem/{printf \"%.0f\", $3/$2 * 100}'")),
            "timestamp": int(time.time())
        })
    except Exception as e:
        return jsonify({
            "status": "unhealthy",
            "error": str(e),
            "timestamp": int(time.time())
        }), 500

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, threaded=True)

PYCODE

# 7. 配置Supervisor
sudo tee /etc/supervisor/conf.d/transcribe.conf > /dev/null <<EOF
[program:transcribe]
command=/transcribe/venv/bin/gunicorn --bind 0.0.0.0:5000 --workers 1 --threads 1 app:app
directory=/transcribe
user=$(whoami)
autostart=true
autorestart=true
environment=BASIC_PITCH_BACKEND="onnxruntime",OMP_NUM_THREADS="1",OPENBLAS_NUM_THREADS="1"
EOF

# 8. 配置Nginx (带HTTPS支持)
sudo tee /etc/nginx/sites-available/transcribe > /dev/null <<EOF
server {
    listen 80;
    server_name _;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl;
    server_name _;
    
    ssl_certificate /etc/letsencrypt/live/$(hostname)/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/$(hostname)/privkey.pem;
    
    client_max_body_size 100m;
    client_body_timeout 600s;
    client_header_timeout 600s;
    
    location / {
        proxy_pass http://127.0.0.1:5000;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        
        proxy_read_timeout 600s;
        proxy_send_timeout 600s;
        
        client_body_buffer_size 1m;
        client_body_temp_path /transcribe/nginx_temp;
    }
    
    location /download/ {
        alias /transcribe/results/;
        expires 1h;
        add_header Cache-Control "public";
    }
}
EOF

sudo ln -sf /etc/nginx/sites-available/transcribe /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default 2>/dev/null || true

# 9. 申请SSL证书 (需要公网IP和域名)
echo "⚠️  需要公网IP和域名才能申请SSL证书"
echo "   请先配置域名解析到您的服务器IP"
read -p "输入您的域名 (或按回车跳过SSL配置): " DOMAIN

if [ ! -z "$DOMAIN" ]; then
    echo "📝 申请SSL证书 for $DOMAIN..."
    sudo certbot --nginx -d $DOMAIN --non-interactive --agree-tos --email admin@$DOMAIN || true
else
    echo "⏭️  跳过SSL证书申请，使用HTTP"
    sudo sed -i 's/listen 443 ssl;/listen 80;/g; /ssl_certificate/d; /ssl_certificate_key/d' /etc/nginx/sites-available/transcribe
    sudo sed -i '/return 301/d' /etc/nginx/sites-available/transcribe
fi

# 10. 重启服务
sudo systemctl restart nginx
sudo supervisorctl reread
sudo supervisorctl update
sudo supervisorctl restart all

# 11. 显示重要信息
echo ""
echo "🎉 部署完成!"
echo ""
echo "🔑 API密钥: $API_KEY"
echo "🌐 服务器地址: http://$(hostname -I | awk '{print $1}')"
[ ! -z "$DOMAIN" ] && echo "🌐 HTTPS地址: https://$DOMAIN"
echo ""
echo "📱 Android应用配置:"
echo "   服务器URL: https://$(hostname -I | awk '{print $1}'):443 (或您的域名)"
echo "   API密钥: $API_KEY"
echo ""
echo "💡 重要安全提示:"
echo "   • 将API密钥保存在安全的地方"
echo "   • 不要将API密钥提交到公共代码仓库"
echo "   • 定期更换API密钥"
echo ""
echo "✅ 验证命令:"
echo "   curl -H 'X-API-Key: $API_KEY' http://localhost/health"
