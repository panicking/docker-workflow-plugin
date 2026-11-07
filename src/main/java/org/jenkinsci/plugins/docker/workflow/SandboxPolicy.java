/*
 * The MIT License
 *
 * Copyright (c) 2026
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.docker.workflow;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jenkinsci.plugins.structs.describable.CustomDescribableModel;
import org.jenkinsci.plugins.structs.describable.UninstantiatedDescribable;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/**
 * Network and resource constraints for a Docker Sandbox created by {@code sbx}.
 *
 * <p>The base network policy is a host-global preset in the {@code sbx} CLI. The
 * step initializes it only on a host where no policy exists yet; it never resets
 * or widens an existing policy. {@link #getAllowedDomains()} are applied as
 * sandbox-scoped allow rules on top of the preset.
 */
public class SandboxPolicy extends AbstractDescribableImpl<SandboxPolicy> implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String ALLOW_ALL = "allow-all";
    public static final String BALANCED = "balanced";
    public static final String DENY_ALL = "deny-all";
    public static final String DEFAULT_NETWORK_POLICY = BALANCED;

    private static final Set<String> NETWORK_POLICIES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(ALLOW_ALL, BALANCED, DENY_ALL)));
    private static final Pattern CPUS = Pattern.compile("[0-9]+(\\.[0-9]+)?");
    private static final Pattern MEMORY = Pattern.compile("[0-9]+[kKmMgGtT]?");

    private String networkPolicy = DEFAULT_NETWORK_POLICY;
    private List<String> allowedDomains = new ArrayList<>();
    /** UI-only representation of {@link #allowedDomains}; hidden from the Snippet Generator. */
    private String allowedDomainsText;
    private String cpus;
    private String memory;

    @DataBoundConstructor
    public SandboxPolicy() {
    }

    public String getNetworkPolicy() {
        return networkPolicy == null ? DEFAULT_NETWORK_POLICY : networkPolicy;
    }

    @DataBoundSetter
    public void setNetworkPolicy(String networkPolicy) {
        String value = Util.fixEmptyAndTrim(networkPolicy);
        this.networkPolicy = value == null ? DEFAULT_NETWORK_POLICY : value;
    }

    public List<String> getAllowedDomains() {
        return allowedDomains == null ? new ArrayList<>() : new ArrayList<>(allowedDomains);
    }

    @DataBoundSetter
    public void setAllowedDomains(List<String> allowedDomains) {
        List<String> copy = new ArrayList<>();
        if (allowedDomains != null) {
            for (String domain : allowedDomains) {
                String value = Util.fixEmptyAndTrim(domain);
                if (value != null && !copy.contains(value)) {
                    copy.add(value);
                }
            }
        }
        this.allowedDomains = copy;
        this.allowedDomainsText = String.join("\n", copy);
    }

    public String getAllowedDomainsText() {
        return allowedDomainsText;
    }

    /**
     * Form-binding alias for {@link #setAllowedDomains(List)}. A textarea is
     * used because Jenkins cannot bind repeated textboxes directly to a
     * {@code List<String>}. Entries are separated by newlines or commas.
     */
    @DataBoundSetter
    public void setAllowedDomainsText(String allowedDomainsText) {
        this.allowedDomainsText = allowedDomainsText == null ? "" : allowedDomainsText;
        this.allowedDomains = parseDomains(allowedDomainsText);
    }

    private static List<String> parseDomains(@CheckForNull String text) {
        List<String> domains = new ArrayList<>();
        if (text != null) {
            for (String part : text.split("[,\\r\\n]+")) {
                String value = Util.fixEmptyAndTrim(part);
                if (value != null && !domains.contains(value)) {
                    domains.add(value);
                }
            }
        }
        return domains;
    }

    @CheckForNull
    public String getCpus() {
        return cpus;
    }

    @DataBoundSetter
    public void setCpus(String cpus) {
        this.cpus = Util.fixEmptyAndTrim(cpus);
    }

    @CheckForNull
    public String getMemory() {
        return memory;
    }

    @DataBoundSetter
    public void setMemory(String memory) {
        this.memory = Util.fixEmptyAndTrim(memory);
    }

    /**
     * Validates the policy values before a sandbox is created.
     *
     * @throws IllegalArgumentException if a value is invalid
     */
    public void validate() {
        String error = checkNetworkPolicy(getNetworkPolicy());
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        error = checkCpus(cpus);
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        error = checkMemory(memory);
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        for (String domain : getAllowedDomains()) {
            error = checkDomain(domain);
            if (error != null) {
                throw new IllegalArgumentException(error);
            }
        }
    }

    private static @CheckForNull String checkNetworkPolicy(@CheckForNull String value) {
        if (value == null || !NETWORK_POLICIES.contains(value)) {
            return "networkPolicy must be one of " + NETWORK_POLICIES;
        }
        return null;
    }

    private static @CheckForNull String checkCpus(@CheckForNull String value) {
        if (value != null && !CPUS.matcher(value).matches()) {
            return "cpus must be a positive number such as '2' or '0.5'";
        }
        return null;
    }

    private static @CheckForNull String checkMemory(@CheckForNull String value) {
        if (value != null && !MEMORY.matcher(value).matches()) {
            return "memory must be a binary size such as '512m' or '4g'";
        }
        return null;
    }

    private static @CheckForNull String checkDomain(@CheckForNull String value) {
        if (value == null || value.isEmpty()) {
            return "allowedDomains must not contain empty entries";
        }
        if (value.indexOf(',') != -1) {
            return "allowedDomains entries must not contain commas: " + value;
        }
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SandboxPolicy)) {
            return false;
        }
        SandboxPolicy that = (SandboxPolicy) o;
        return getNetworkPolicy().equals(that.getNetworkPolicy())
                && getAllowedDomains().equals(that.getAllowedDomains())
                && Objects.equals(cpus, that.cpus)
                && Objects.equals(memory, that.memory);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getNetworkPolicy(), getAllowedDomains(), cpus, memory);
    }

    @Override
    public String toString() {
        return "SandboxPolicy{networkPolicy='" + getNetworkPolicy() + "', allowedDomains=" + getAllowedDomains()
                + ", cpus='" + cpus + "', memory='" + memory + "'}";
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<SandboxPolicy> implements CustomDescribableModel {

        @Override
        public String getDisplayName() {
            return "Docker Sandbox";
        }

        @Override
        public Map<String, Object> customInstantiate(Map<String, Object> arguments) {
            Map<String, Object> copy = new HashMap<>(arguments);
            Object text = copy.remove("allowedDomainsText");
            if (!copy.containsKey("allowedDomains") && text != null) {
                copy.put("allowedDomains", parseDomains(text.toString()));
            }
            return copy;
        }

        @Override
        public UninstantiatedDescribable customUninstantiate(UninstantiatedDescribable ud) {
            Map<String, Object> arguments = new TreeMap<>(ud.getArguments());
            arguments.remove("allowedDomainsText");
            return ud.withArguments(arguments);
        }

        public ListBoxModel doFillNetworkPolicyItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Allow all", ALLOW_ALL);
            items.add("Balanced", BALANCED);
            items.add("Deny all", DENY_ALL);
            return items;
        }

        public FormValidation doCheckNetworkPolicy(@QueryParameter String value) {
            String error = checkNetworkPolicy(value);
            return error == null ? FormValidation.ok() : FormValidation.error(error);
        }

        public FormValidation doCheckCpus(@QueryParameter String value) {
            String error = checkCpus(Util.fixEmptyAndTrim(value));
            return error == null ? FormValidation.ok() : FormValidation.error(error);
        }

        public FormValidation doCheckMemory(@QueryParameter String value) {
            String error = checkMemory(Util.fixEmptyAndTrim(value));
            return error == null ? FormValidation.ok() : FormValidation.error(error);
        }
    }
}
