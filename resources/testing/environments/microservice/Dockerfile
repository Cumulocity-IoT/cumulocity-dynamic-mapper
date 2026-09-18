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

# Use gunicorn for production; 2 workers is plenty for a test microservice
CMD ["gunicorn", "--bind", "0.0.0.0:80", "--workers", "2", "--timeout", "60", "app:app"]
