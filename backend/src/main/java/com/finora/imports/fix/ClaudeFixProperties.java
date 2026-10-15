package com.finora.imports.fix;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * The automatic Claude fix job (docs/superpowers/specs/2026-10-15-claude-fix-job-design.md). Off by
 * default: while off nothing is started, sent or polled. The token is the owner's fine-grained
 * token for the private repository only (Contents write, Actions read, no Workflows); it is never
 * logged.
 */
@Configuration
@ConfigurationProperties(prefix = "app.claude-fix")
public class ClaudeFixProperties {

    private boolean enabled = false;
    private int monthlyCap = 50;
    private String githubToken = "";
    private String privateRepo = "Fynora/finora-statement-traces";
    private String publicRepo = "Fynora/finora";
    private String incomingBranch = "incoming";
    private String apiBase = "https://api.github.com";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMonthlyCap() { return monthlyCap; }
    public void setMonthlyCap(int monthlyCap) { this.monthlyCap = monthlyCap; }
    public String getGithubToken() { return githubToken; }
    public void setGithubToken(String githubToken) { this.githubToken = githubToken == null ? "" : githubToken; }
    public String getPrivateRepo() { return privateRepo; }
    public void setPrivateRepo(String privateRepo) { this.privateRepo = privateRepo; }
    public String getPublicRepo() { return publicRepo; }
    public void setPublicRepo(String publicRepo) { this.publicRepo = publicRepo; }
    public String getIncomingBranch() { return incomingBranch; }
    public void setIncomingBranch(String incomingBranch) { this.incomingBranch = incomingBranch; }
    public String getApiBase() { return apiBase; }
    public void setApiBase(String apiBase) { this.apiBase = apiBase; }

    @Override
    public String toString() {
        // Never the token.
        return "ClaudeFixProperties{enabled=" + enabled + ", monthlyCap=" + monthlyCap + ", privateRepo=" + privateRepo
                + ", publicRepo=" + publicRepo + ", incomingBranch=" + incomingBranch + ", apiBase=" + apiBase + "}";
    }
}
