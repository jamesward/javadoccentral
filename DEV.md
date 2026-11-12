# Dev Info

Run with restart, Postgres via Testcontainers & optionally mock inference:
```
./sbta ~Test/runReload
# or with local zio-http-mcp and zio-mavencentral projects
./sbta -Dlocal ~Test/runReload
```

Run with restart, prod config:
```
./sbta ~runReload
```
