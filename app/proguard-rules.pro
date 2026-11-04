# 本模块的混淆规则（release 目前 isMinifyEnabled = false，留空即可）
# 以后开混淆时，kotlinx.serialization 的 @Serializable 类需要保留序列化器，
# 常用规则（kotlinx.serialization 1.7 自带 consumer rules，一般不用手写）：
# -keepattributes *Annotation*, InnerClasses
# -dontnote kotlinx.serialization.**
