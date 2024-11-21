FROM redis:7.4.1

COPY redis.conf /etc/redis/redis.conf
COPY users.acl /etc/redis/users.acl

CMD ["redis-server", "/etc/redis/redis.conf"]
