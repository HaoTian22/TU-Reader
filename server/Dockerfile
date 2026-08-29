FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

# No third-party dependencies: the feedback server is standard library only.
COPY app.py ./app.py
RUN mkdir -p /app/data

EXPOSE 8000

CMD ["python", "app.py"]
