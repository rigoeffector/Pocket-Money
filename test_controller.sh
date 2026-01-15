#!/bin/bash
# Test if controller is accessible
curl -v -X GET "http://localhost:8383/api/efashe/settings" \
  -H "Authorization: eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJhZG1pbiIsInJvbGUiOiJBRE1JTiIsInR5cGUiOiJBVVRIIiwiaWF0IjoxNzY4NTAwMTY3LCJleHAiOjE3NzEwOTIxNjd9.TC9N539TBOesQkrjw26p4RfDWNHvMpT6HQbaKS__aZ0SIlIRMWNtbltubkIXrYV71Hw-KY1zlZisD9Ag0gvxGg" \
  -H "Content-Type: application/json" 2>&1 | grep -E "HTTP|404|200|mappings"
