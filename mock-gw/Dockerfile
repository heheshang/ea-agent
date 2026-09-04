# Mock SMS gateway (simulated real vendor)
# - POST /sms/send     <- EA platform adapter calls this (form + X-Api-Key)
# - POST /sms/receipt  <- receipt query
# - after a send, asynchronously calls back the platform's callback endpoint
#   POST /api/channels/sms/callback with HMAC-SHA256 signature (canonical: messageId|status|timestamp)
FROM python:3.12-slim
WORKDIR /app
COPY mock_gw.py /app/mock_gw.py
EXPOSE 8090
CMD ["python", "-u", "/app/mock_gw.py"]