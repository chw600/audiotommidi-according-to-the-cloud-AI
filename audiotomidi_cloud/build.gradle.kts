plugins {
    id("com.android.application") version "8.2.0" apply false
    id("com.android.library") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.0" apply false
}

allprojects {
    configurations.all {
        resolutionStrategy {
            force("org.bouncycastle:bcprov-jdk18on:1.78") // 解决Chaquopy依赖冲突
        }
    }
}