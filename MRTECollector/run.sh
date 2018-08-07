./MRTECollector \
  -mongouri='mongodb://mongo-queue-db:30000?connect=direct' \
  -mongodb="mrte" \
  -mongocollection="mrte" \
  -threads=5 \
  -fastparsing=true \
  -mysqluri='mrte2:mrte2@tcp(127.0.0.1:3306)/'
