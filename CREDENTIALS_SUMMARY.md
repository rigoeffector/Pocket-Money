# Credentials Summary

## Database Credentials

### Local Development
- **Host:** localhost:5432
- **Database:** pocketmoney_db
- **Username:** postgres
- **Password:** amazimeza12QW!@

### Production/Remote
- **Host:** localhost:5432 (on server 164.92.89.74)
- **Database:** pocketmoney_db
- **Username:** postgres
- **Password:** amazimeza12QW!@

## EFASHE API Credentials

### Configuration (from application.properties)
- **API URL:** https://sb-api.efashe.com/rw/v2
- **API Key:** 6a66e55b-3c9c-4d0c-9a35-025c5def77fd
- **API Secret:** c06200df-c55c-416d-b9af-43568b5abac2

## MoPay API Credentials

### Development
- **API URL:** https://api.mopay.rw
- **API Token:** 2fuytPgoD4At0FE1MgoF08xuAr03xSvkJ1ZlGrT5jYFyolQsBU7XKU28OW4Oqq3a

### Production
- **API URL:** https://api.mopay.rw
- **API Token:** ${MOPAY_API_TOKEN:2fuytPgoD4At0FE1MgoF08xuAr03xSvkJ1ZlGrT5jYFyolQsBU7XKU28OW4Oqq3a}

## SMS Configuration

### Swift.com (Default)
- **API URL:** https://swiftqom.io/api/dev
- **API Key (Dev):** SWQWEkheqFdx31PXeKXbVT9MlTU8jzs7Sgtf3ovpkzxb5dimWLTCx9FLLjnZc4YS
- **API Key (BEPAY):** SWQcYaV1v5ZDPXlABkhvECKsiyskASwOk3gz6tUPoLarxpUcyeoUE5viI8U4pKJM
- **Sender ID:** BEPAY

### Bepay/BeSoft (Alternative)
- **API Key:** SWQj29yWXdjcjJMzhO3bkk6DNqxrpq6tJ9ZmB21SLZxGmuNhcPXIjVJSwlSeL4uD
- **Sender ID:** besoftsms
- **URL:** http://api.rmlconnect.net:8080/bulksms/bulksms?type=0&dlr=1
- **Password:** Musso77!

## WhatsApp API

- **API URL:** https://server.yunotify.com/api
- **API Key:** ${WHATSAPP_API_KEY:} (from environment variable)

## JWT Configuration

- **Secret:** SSZahduoMw4hnhkwXvXSOBTVoTk2rGvK0NGS3E+8dfTiWxnPoB5UczVtW47aU8kpyxlrKa5kjCThTIfrMlqz6A==
- **Expiration:** 2592000000 ms (30 days)
- **Refresh Expiration:** 604800000 ms (7 days)
