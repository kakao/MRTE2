#!/bin/bash

CLASSPATH=.:./build/
for jar in ./lib/*.jar; do
    CLASSPATH=$CLASSPATH:$jar
done

## only redirect error log to file (error.log)
java \
-XX:+UseParNewGC -XX:+UseConcMarkSweepGC -XX:NewSize=1024M -XX:SurvivorRatio=3 -XX:MaxTenuringThreshold=3 \
-XX:+CMSParallelRemarkEnabled -XX:CMSInitiatingOccupancyFraction=70 -XX:+UseCMSInitiatingOccupancyOnly \
-XX:+PrintGCDetails -XX:+PrintGCDateStamps -XX:+PrintTenuringDistribution -XX:+PrintGCApplicationStoppedTime \
-Xloggc:mrte_player_gc.log -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=10 -XX:GCLogFileSize=20M \
-Xmx2G -Xms2G -cp $CLASSPATH mrte.MRTEPlayer \
--mysql_url='jdbc:mysql://127.0.0.1:3306/mysqlslap?user=mrte2&password=mrte2' \
  --mysql_init_connections=50 \
  --mysql_default_db="mysqlslap" \
  --mongo_url='mongodb://mongo-queue-db:30000/mrte?connectTimeoutMS=300000&authSource=admin' \
  --mongo_db="mrte" \
  --mongo_collectionprefix="mrte" \
  --mongo_collections=5 \
  --slow_query_time=100 \
  --verbose=true 2> error.log


## Connect mongodb without auth
## --mongo_url="mongodb://mongo-queue-db:30000/mrte?connectTimeoutMS=300000" \
## Connect mongodb with auth
## --mongo_url="mongodb://username:password@mongo-queue-db:30000/mrte?connectTimeoutMS=300000&authSource=admin" \
