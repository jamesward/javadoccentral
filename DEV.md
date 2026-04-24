# Dev Info

Run with restart, embedded Redis & optionally mock inference:
```
./sbt ~reStartTest
# or with local zio-http-mcp and zio-mavencentral projects
./sbt -Dlocal ~reStartTest
```

Run with restart, prod config:
```
./sbt ~reStart
```

Build the container:
```
pack build --builder=paketobuildpacks/builder-jammy-base \
 javadoccentral
```

Run the container:
```
docker run -p8080:8080 -m 512m javadoccentral
```
