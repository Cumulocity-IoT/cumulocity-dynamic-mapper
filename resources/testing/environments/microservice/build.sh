#!/usr/bin/env bash
# build.sh — builds and packages the my-processor microservice as a Cumulocity ZIP.
#
# Usage:
#   ./build.sh [--push]
#
# Without --push  : builds the Docker image locally and creates the deploy ZIP.
# With    --push  : also uploads the ZIP to the connected Cumulocity tenant via
#                   the go-c8y-cli tool (c8y).  Requires C8Y_BASEURL / C8Y_USER /
#                   C8Y_PASSWORD (or a session configured via c8y sessions) to be set.
#
# Output:  my-processor.zip  (in the current directory)
set -euo pipefail

IMAGE_NAME="my-processor"
IMAGE_TAG="0.0.1-SNAPSHOT"
ZIP_NAME="${IMAGE_NAME}.zip"
PLATFORM="${BUILD_PLATFORM:-linux/amd64}"

cd "$(dirname "$0")"

echo "==> Building Docker image ${IMAGE_NAME}:${IMAGE_TAG} for ${PLATFORM}"
docker build \
  --platform "${PLATFORM}" \
  --tag "${IMAGE_NAME}:${IMAGE_TAG}" \
  .

echo "==> Saving Docker image to image.tar"
docker save "${IMAGE_NAME}:${IMAGE_TAG}" -o image.tar

echo "==> Generating cumulocity.json (name=${IMAGE_NAME}, version=${IMAGE_TAG})"
cat > cumulocity.json <<EOF
{
  "apiVersion": "2",
  "version": "${IMAGE_TAG}",
  "name": "${IMAGE_NAME}",
  "contextPath": "${IMAGE_NAME}",
  "provider": {
    "name": "Dynamic Mapper Test"
  },
  "buildSpec": {
    "targetBuildArchitectures": [
      "${PLATFORM}"
    ]
  },
  "isolation": "PER_TENANT",
  "resources": {
    "cpu": "0.1",
    "memory": "256M"
  },
  "requiredRoles": [],
  "livenessProbe": {
    "httpGet": {
      "path": "/health"
    },
    "initialDelaySeconds": 30,
    "periodSeconds": 10
  },
  "readinessProbe": {
    "httpGet": {
      "path": "/health",
      "port": 80
    },
    "initialDelaySeconds": 30,
    "periodSeconds": 10
  }
}
EOF

echo "==> Creating ${ZIP_NAME}"
zip -j "${ZIP_NAME}" cumulocity.json image.tar
rm -f image.tar

echo "==> Done: ${ZIP_NAME}"

if [[ "${1:-}" == "--push" ]]; then
  echo "==> Uploading ${ZIP_NAME} to Cumulocity tenant"
  c8y microservices create --file "${ZIP_NAME}" --timeout 360
  echo "==> Upload complete"
fi
