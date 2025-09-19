#!/bin/bash

echo "🚀 Setting up Any Cloud Management development environment..."

# Set proper permissions
sudo chown -R vscode:vscode /workspace
sudo chmod -R 755 /workspace

# Update Java version in build.gradle to Java 21
echo "📝 Updating Java version to 21 in build.gradle..."
sed -i 's/sourceCompatibility = '\''17'\''/sourceCompatibility = '\''21'\''/g' /workspace/build.gradle

# Make gradlew executable
chmod +x /workspace/gradlew

# Install dependencies
echo "📦 Installing Gradle dependencies..."
cd /workspace
./gradlew clean build -x test

# Wait for database to be ready
echo "⏳ Waiting for MariaDB to be ready..."
timeout=60
counter=0
while ! nc -z anycloud-db 3306; do
    if [ $counter -eq $timeout ]; then
        echo "❌ Database connection timeout"
        exit 1
    fi
    echo "Waiting for database... ($counter/$timeout)"
    sleep 1
    counter=$((counter + 1))
done

echo "✅ Database is ready!"

# Create application properties for development
echo "📝 Creating development application properties..."
cat > /workspace/anycloud/src/main/resources/application-dev.properties << 'EOF'
# Development Database Configuration
spring.datasource.url=jdbc:mariadb://anycloud-db:3306/anycloud?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul
spring.datasource.username=anycloud
spring.datasource.password=anycloud
spring.datasource.driver-class-name=org.mariadb.jdbc.Driver

# JPA Configuration
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MariaDBDialect

# Server Configuration
server.port=8888
server.servlet.context-path=/

# Logging
logging.level.com.aipaas.anycloud=DEBUG
logging.level.org.springframework.web=DEBUG
logging.level.org.hibernate.SQL=DEBUG
logging.level.org.hibernate.type.descriptor.sql.BasicBinder=TRACE

# External Services
thanos.url=https://localhost:9000
EOF

# Set development profile
echo "🔧 Setting development profile..."
export SPRING_PROFILES_ACTIVE=dev

# Create a simple startup script
echo "📝 Creating startup script..."
cat > /workspace/start-dev.sh << 'EOF'
#!/bin/bash
echo "🚀 Starting Any Cloud Management in development mode..."
export SPRING_PROFILES_ACTIVE=dev
./gradlew bootRun
EOF

chmod +x /workspace/start-dev.sh

# Create a debug startup script
echo "📝 Creating debug startup script..."
cat > /workspace/start-debug.sh << 'EOF'
#!/bin/bash
echo "🐛 Starting Any Cloud Management in debug mode..."
export SPRING_PROFILES_ACTIVE=dev
export JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:8080"
./gradlew bootRun
EOF

chmod +x /workspace/start-debug.sh

echo "✅ Development environment setup complete!"
echo ""
echo "📋 Available commands:"
echo "  ./start-dev.sh     - Start the application in development mode"
echo "  ./start-debug.sh   - Start the application in debug mode (port 8080)"
echo "  ./gradlew bootRun  - Run with Gradle"
echo "  ./gradlew build    - Build the project"
echo "  ./gradlew test     - Run tests"
echo ""
echo "🌐 Application will be available at: http://localhost:8888"
echo "🗄️  Database is available at: localhost:3306"
echo "🐛 Debug port: 8080"
echo ""
echo "Happy coding! 🎉"
