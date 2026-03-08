FROM python:3.11-slim

# Instalação do Apache Bench
RUN apt-get update && apt-get install -y apache2-utils && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY compose/benchmark-ui/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# O código em scripts e assets
COPY compose/benchmark-ui/app/ ./app/
COPY scripts/benchmark.py ./scripts/benchmark.py

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
