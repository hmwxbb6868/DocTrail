package com.doctrail.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Environment-backed credentials used only by the stage-2 bootstrap profile. */
@Component
@ConfigurationProperties(prefix = "doctrail.auth.bootstrap")
//代表只读取配置里以 doctrail.auth.bootstrap 开头的属性。
public class AuthBootstrapProperties {
    private boolean required;
    private String demoUsername = "";
    private String demoPassword = "";
    private String adminUsername = "";
    private String adminPassword = "";

    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public String getDemoUsername() { return demoUsername; }
    public void setDemoUsername(String demoUsername) { this.demoUsername = demoUsername; }
    public String getDemoPassword() { return demoPassword; }
    public void setDemoPassword(String demoPassword) { this.demoPassword = demoPassword; }
    public String getAdminUsername() { return adminUsername; }
    public void setAdminUsername(String adminUsername) { this.adminUsername = adminUsername; }
    public String getAdminPassword() { return adminPassword; }
    public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }
}
