# R8 keep 规则（自用 release 包）
# 主流库（Compose / Room / OkHttp / Media3 / Coil）自带 consumer rules，这里只做保险与收敛。

# 应用数据模型（org.json 手动解析，但保留以防反射/序列化路径）
-keep class com.ivan.cinema.data.** { *; }
-keep class com.ivan.cinema.db.** { *; }

# Room 生成的实现
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# OkHttp / Okio（部分版本需要）
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Media3 可选组件缺失告警收敛
-dontwarn androidx.media3.**

# Kotlin 协程
-dontwarn kotlinx.coroutines.**

# 保留行号（便于真机排障，体积代价极小）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
