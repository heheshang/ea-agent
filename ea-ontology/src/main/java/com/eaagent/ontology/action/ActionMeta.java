package com.eaagent.ontology.action;

import java.util.Map;

/**
 * Action 元信息（3.4）：名称 / 描述 / 必需参数 / 需要的权限。
 */
public record ActionMeta(
        String name,
        String description,
        java.util.List<String> requiredArgs,
        java.util.List<String> permissions) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;
        private String description;
        private java.util.List<String> requiredArgs = java.util.List.of();
        private java.util.List<String> permissions = java.util.List.of();

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder requiredArgs(java.util.List<String> requiredArgs) {
            this.requiredArgs = requiredArgs;
            return this;
        }

        public Builder permissions(java.util.List<String> permissions) {
            this.permissions = permissions;
            return this;
        }

        public ActionMeta build() {
            return new ActionMeta(name, description, requiredArgs, permissions);
        }
    }
}