# Dev Info

Run with restart, embedded Valkey & optionally mock inference:
```
./sbt ~Test/runReload
# or with local zio-http-mcp and zio-mavencentral projects
./sbt -Dlocal ~Test/runReload
```

Run with restart, prod config:
```
./sbt ~runReload
```
