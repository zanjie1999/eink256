# 设置构建所需的最小 CMake 版本
cmake_minimum_required(VERSION 3.10.2)

# 声明项目名称
project("zyymeEink256")

# 创建共享库 (Shared Library, 即 .so 文件)
add_library(
        zyymeEink256 # 库名称
        SHARED     # 库类型

        # 源文件路径
        eink256.cpp
)

# 查找系统预构建库
find_library(
        log-lib
        log
)

find_library(
        jnigraphics-lib
        jnigraphics # 关键：用于 AndroidBitmap_* 系列函数
)

# 链接库
target_link_libraries(
        zyymeEink256
        ${log-lib}
        ${jnigraphics-lib}
)