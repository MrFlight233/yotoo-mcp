#!/bin/bash
# Spring Boot 3.x 需要 JDK 17+。若本机路径不同，请修改 JAVA_HOME；路径不存在时会落到系统 java，易出现 UnsupportedClassVersionError（用成了 Java 8）。
export JAVA_HOME=/opt/jservices/micros/mcp-server/java17/jdk-17.0.19+10
export PATH="$JAVA_HOME/bin:$PATH"

JAVA_BIN="$JAVA_HOME/bin/java"
require_java17() {
    if [ ! -x "$JAVA_BIN" ]; then
        echo "错误: 未找到可执行的 Java: $JAVA_BIN"
        echo "请在本机安装 JDK 17+，并将脚本中的 JAVA_HOME 改为实际安装目录。"
        exit 1
    fi
    # 取主版本号（匹配 openjdk version "17.0.x" / java version "1.8.0" 等首段数字）
    ver="$("$JAVA_BIN" -version 2>&1 | head -1 | sed -n 's/.* version "\([0-9]*\).*/\1/p')"
    if ! [[ "$ver" =~ ^[0-9]+$ ]] || [ "$ver" -lt 17 ]; then
        echo "错误: 需要 JDK 17 或更高版本，当前 JAVA_HOME 下 java 解析到的主版本号为: ${ver:-未知}（Java 8 常为 1）"
        echo "JAVA_HOME=$JAVA_HOME"
        "$JAVA_BIN" -version 2>&1 || true
        exit 1
    fi
}

#这里可替换为jar包名字
APP_NAME=yotoo-mcp-1.0.0.jar
SERVER_PORT=10087
# 安装目录：日志、tmp 均在此下；与 -Djava.io.tmpdir 必须一致，且启动前会 mkdir -p
APP_BASE=/opt/jservices/micros/mcp-server
APP_TMP="$APP_BASE/tmp"
#根据实际情况修改参数----Djava.io.tmpdir 为 Tomcat/Spring 临时目录，父目录不存在会报 NoSuchFileException
#JVM="-Xms64m -Xmx128m -XX:MaxMetaspaceSize=192m -XX:CompressedClassSpaceSize=32m -Xss1m -Xmn32m -XX:InitialCodeCacheSize=32m -XX:ReservedCodeCacheSize=32m -XX:MaxDirectMemorySize=64m -Duser.timezone=GMT+08"
JVM="-Xms256m -Xmx512m -XX:MaxMetaspaceSize=768m -XX:CompressedClassSpaceSize=128m -Xss4m -Xmn128m -XX:InitialCodeCacheSize=128m -XX:ReservedCodeCacheSize=128m -XX:MaxDirectMemorySize=256m -Duser.timezone=GMT+08 -Djava.io.tmpdir=$APP_TMP"
#JVM="-Xms512m -Xmx1024m -XX:MaxMetaspaceSize=1536m -XX:CompressedClassSpaceSize=256m -Xss8m -Xmn256m -XX:InitialCodeCacheSize=256m -XX:ReservedCodeCacheSize=256m -XX:MaxDirectMemorySize=512m -Duser.timezone=GMT+08"

ensure_app_dirs() {
    if ! mkdir -p "$APP_TMP"; then
        echo "错误: 无法创建临时目录（java.io.tmpdir）: $APP_TMP"
        exit 1
    fi
}

#使用说明,用来提示输入参数 
usage() { 
    echo "Usage: sh 执行脚本.sh [start|stop|restart|status]" 
    exit 1 
} 
#检查程序是否在运行 
is_exist(){ 
    pid=`ps -ef|grep $APP_NAME|grep -v grep|awk '{print $2}' ` 
    #如果不存在返回1,存在返回0 
    if [ -z "${pid}" ]; then 
        return 1 
    else 
        return 0 
    fi 
} 
#启动方法 
start(){ 
    require_java17
    ensure_app_dirs
    is_exist 
    if [ $? -eq "0" ]; then 
        echo "${APP_NAME} is already running. pid=${pid} ." 
    else 
        nohup "$JAVA_BIN" $JVM -jar $APP_NAME --server.port=$SERVER_PORT > "$APP_BASE/yotoo-mcp.log" 2>&1 &
        is_exist
        if [ $? -eq "0" ]; then
            echo "${APP_NAME} is started. pid=${pid}"
        #else
           # echo "${APP_NAME} start fail!!!."
        fi          
    fi
} 
#停止方法 
stop(){ 
    is_exist 
    if [ $? -eq "0" ]; then 
        kill -9 $pid 
    else 
        echo "${APP_NAME} is not running" 
    fi 
} 
#输出运行状态 
status(){ 
    is_exist 
    if [ $? -eq "0" ]; then 
        echo "${APP_NAME} is running. Pid is ${pid}" 
    else 
        echo "${APP_NAME} is NOT running." 
    fi 
} 
#重启 
restart(){ 
    stop 
    start 
} 
#根据输入参数,选择执行对应方法,不输入则执行使用说明 
case "$1" in 
    "start") 
        start 
        ;; 
    "stop") 
        stop 
        ;; 
    "status") 
        status 
        ;; 
    "restart") 
        restart 
        ;; 
    *) 
usage 
;; 
esac