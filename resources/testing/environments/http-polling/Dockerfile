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

# Use a single worker so the in-memory request log remains complete and clearable
CMD ["gunicorn", "--bind", "0.0.0.0:80", "--workers", "1", "--timeout", "60", "app:app"]
