FROM quay.io/keycloak/keycloak:26.0
COPY target/keycloak-bohemia-gameaccount-*.jar /opt/keycloak/providers/
COPY src/main/resources/theme/eliferpg-reforger /opt/keycloak/themes/eliferpg-reforger
