#!/bin/bash

TARGETPATH=/data1/MRTE2/MRTEPlayer/build
CLASSPATH=.
for jar in /data1/MRTE2/MRTEPlayer/lib/*.jar; do
    CLASSPATH=$CLASSPATH:$jar
done

pushd src

echo "javac -cp $CLASSPATH mrte/*.java =d $TARGETPATH"
javac -cp $CLASSPATH mrte/*.java -d $TARGETPATH

popd
