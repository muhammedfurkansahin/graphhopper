# OpenJDK 17 tabanlı bir Docker imajı kullan
FROM openjdk:17

# Çalışma dizinini belirle
WORKDIR /app

# Gerekli dosyaları Docker imajına kopyala
COPY ./graphhopper-web-11.0-SNAPSHOT.jar /app/graphhopper.jar
COPY ./gtfs.zip /app/gtfs.zip
COPY ./config.yml /app/config.yml
COPY ./turkey-latest.osm.pbf /app/turkey-latest.osm.pbf
COPY ./custom_pt.json /app/custom_pt.json
COPY ./custom_car.json /app/custom_car.json
COPY ./custom_foot.json /app/custom_foot.json
COPY ./custom_foot_elevation.json /app/custom_foot_elevation.json
COPY ./custom_bus.json /app/custom_bus.json

# GraphHopper'ı başlatan komut
CMD ["java", "-Xmx8g", "-Xms2g", "-XX:+UseG1GC", "-jar", "/app/graphhopper.jar", "server", "/app/config.yml"]