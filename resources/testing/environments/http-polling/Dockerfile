FROM python:3.12-slim

# Create a non-root user for the application
RUN useradd --no-create-home --shell /bin/false appuser

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py .

USER appuser

# Cumulocity routes incoming requests to port 80 inside the container
ENV SERVER_PORT=80

EXPOSE 80

# Single worker *process* so the in-memory request log / event store stay in one place
# (each gunicorn worker process would otherwise have its own independent copy) — but
# multiple threads within it, so concurrent poll requests from two connectors (or two
# topics on the same connector) don't serialize behind gunicorn's sync worker and risk
# tripping the connector's own 30s per-poll REQUEST_TIMEOUT. app.py's shared mutable
# state (_events_store/_event_seq) is guarded by a lock for exactly this concurrency.
CMD ["gunicorn", "--bind", "0.0.0.0:80", "--workers", "1", "--worker-class", "gthread", "--threads", "4", "--timeout", "60", "app:app"]
