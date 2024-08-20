FROM amazoncorretto:21-alpine
COPY /build/libs/calendar-all.jar /app/calendar.jar
ENTRYPOINT ["java", "-jar", "/app/calendar.jar"]
