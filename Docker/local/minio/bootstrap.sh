#!/bin/sh
set -eu

# Never enable shell tracing: commands contain local bootstrap credentials.
export MC_CONFIG_DIR=/tmp/mc
service="${1:-education}"
case "$service" in
  education)
    bucket="$EDUCATION_S3_BUCKET"
    access_key="$EDUCATION_S3_ACCESS_KEY"
    secret_key="$EDUCATION_S3_SECRET_KEY"
    object_resources="\"arn:aws:s3:::$bucket/materials/*\",\"arn:aws:s3:::$bucket/chat/*\",\"arn:aws:s3:::$bucket/tmp/*\""
    ;;
  assignment)
    bucket="$ASSIGNMENT_S3_BUCKET"
    access_key="$ASSIGNMENT_S3_ACCESS_KEY"
    secret_key="$ASSIGNMENT_S3_SECRET_KEY"
    object_resources="\"arn:aws:s3:::$bucket/files/*\""
    case "$bucket" in
      ''|*[!a-z0-9-]*|-*|*-) echo 'Invalid Assignment S3 bucket' >&2; exit 1 ;;
    esac
    if [ "${#bucket}" -lt 3 ] || [ "${#bucket}" -gt 63 ]; then
      echo 'Invalid Assignment S3 bucket length' >&2
      exit 1
    fi
    if [ "$bucket" = "$EDUCATION_S3_BUCKET" ] || [ "$access_key" = "$EDUCATION_S3_ACCESS_KEY" ]; then
      echo 'Assignment must use its own bucket and S3 user' >&2
      exit 1
    fi
    ;;
  *) echo 'Unknown storage service' >&2; exit 1 ;;
esac
case "$bucket" in
  ''|*[!a-z0-9.-]*) echo 'Invalid S3 bucket' >&2; exit 1 ;;
esac
if [ -z "$access_key" ] || [ -z "$secret_key" ] || [ "$access_key" = "$MINIO_ROOT_USER" ]; then
  echo 'An S3 user and secret distinct from the root account are required' >&2
  exit 1
fi
mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null 2>&1
attempt=0
until mc ready local >/dev/null 2>&1; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 60 ]; then
    echo 'MinIO bootstrap failed: server unavailable or bootstrap credentials rejected' >&2
    exit 1
  fi
  sleep 2
done

mc mb --ignore-existing "local/$bucket" >/dev/null
mc anonymous set none "local/$bucket" >/dev/null
cat > "/tmp/$service-policy.json" <<EOF
{"Version":"2012-10-17","Statement":[
  {"Effect":"Allow","Action":["s3:GetBucketLocation","s3:ListBucket"],"Resource":["arn:aws:s3:::$bucket"]},
  {"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:DeleteObject","s3:AbortMultipartUpload","s3:ListMultipartUploadParts"],
   "Resource":[$object_resources]}
]}
EOF
mc admin user add local "$access_key" "$secret_key" >/dev/null 2>&1
mc admin policy create local "$service-storage" "/tmp/$service-policy.json" >/dev/null
mc admin policy attach local "$service-storage" --user "$access_key" >/dev/null
echo "$service private bucket and restricted S3 user initialized"
