FROM quay.io/keycloak/keycloak:26.0
COPY target/keycloak-bohemia-gameaccount-*.jar /opt/keycloak/providers/
