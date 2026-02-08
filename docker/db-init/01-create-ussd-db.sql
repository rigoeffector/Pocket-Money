SELECT 'CREATE DATABASE lottery_db'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'lottery_db')
\gexec
