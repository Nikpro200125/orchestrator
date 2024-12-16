# Используем официальный базовый образ OpenJDK
FROM openjdk:21-jdk-slim

# Устанавливаем рабочую директорию внутри контейнера
WORKDIR /app

EXPOSE 8080

# Копируем сгенерированный проект в контейнер
COPY target/*.jar app.jar

# Команда для запуска приложения
ENTRYPOINT ["java", "-jar", "app.jar"]
