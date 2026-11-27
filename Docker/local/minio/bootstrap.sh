#!/bin/sh
set -eu

# Never enable shell tracing: commands contain local bootstrap credentials.
export MC_CONFIG_DIR=/tmp/mc
bucket="$EDUCATION_S3_BUCKET"
case "$bucket" in
  ''|*[!a-z0-9.-]*) echo 'Invalid EDUCATION_S3_BUCKET' >&2; exit 1 ;;
esac
if [ "$EDUCATION_S3_ACCESS_KEY" = "$MINIO_ROOT_USER" ]; then
  echo 'Education must use a separate S3 user' >&2
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
cat > /tmp/education-policy.json <<EOF
{"Version":"2012-10-17","Statement":[
  {"Effect":"Allow","Action":["s3:GetBucketLocation","s3:ListBucket"],"Resource":["arn:aws:s3:::$bucket"]},
  {"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:DeleteObject","s3:AbortMultipartUpload","s3:ListMultipartUploadParts"],
   "Resource":["arn:aws:s3:::$bucket/materials/*","arn:aws:s3:::$bucket/chat/*","arn:aws:s3:::$bucket/tmp/*"]}
]}
EOF
mc admin user add local "$EDUCATION_S3_ACCESS_KEY" "$EDUCATION_S3_SECRET_KEY" >/dev/null 2>&1
mc admin policy create local education-storage /tmp/education-policy.json >/dev/null
mc admin policy attach local education-storage --user "$EDUCATION_S3_ACCESS_KEY" >/dev/null
echo 'Education private bucket and restricted S3 user initialized'
