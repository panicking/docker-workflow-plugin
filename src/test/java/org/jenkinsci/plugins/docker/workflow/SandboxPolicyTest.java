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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class SandboxPolicyTest {

    @Test
    public void defaults() {
        SandboxPolicy policy = new SandboxPolicy();
        assertEquals(SandboxPolicy.BALANCED, policy.getNetworkPolicy());
        assertEquals(0, policy.getAllowedDomains().size());
        assertEquals(null, policy.getCpus());
        assertEquals(null, policy.getMemory());
        policy.validate();
    }

    @Test
    public void setters() {
        SandboxPolicy policy = new SandboxPolicy();
        policy.setNetworkPolicy("deny-all");
        policy.setAllowedDomains(Arrays.asList(" repo.maven.apache.org ", "github.com", "repo.maven.apache.org", ""));
        policy.setCpus(" 2 ");
        policy.setMemory(" 4g ");
        assertEquals("deny-all", policy.getNetworkPolicy());
        assertEquals(Arrays.asList("repo.maven.apache.org", "github.com"), policy.getAllowedDomains());
        assertEquals("repo.maven.apache.org\ngithub.com", policy.getAllowedDomainsText());
        assertEquals("2", policy.getCpus());
        assertEquals("4g", policy.getMemory());
        policy.validate();
    }

    @Test
    public void parsesAllowedDomainsText() {
        SandboxPolicy policy = new SandboxPolicy();
        policy.setAllowedDomainsText(" a.example.com,\n b.example.com \n\n a.example.com ");
        assertEquals(Arrays.asList("a.example.com", "b.example.com"), policy.getAllowedDomains());
        policy.validate();
    }

    @Test
    public void nullNetworkPolicyFallsBackToDefault() {
        SandboxPolicy policy = new SandboxPolicy();
        policy.setNetworkPolicy(null);
        assertEquals(SandboxPolicy.BALANCED, policy.getNetworkPolicy());
    }

    @Test
    public void defensiveCopies() {
        List<String> input = new ArrayList<>(Arrays.asList("a.example.com"));
        SandboxPolicy policy = new SandboxPolicy();
        policy.setAllowedDomains(input);
        input.add("b.example.com");
        assertEquals(Arrays.asList("a.example.com"), policy.getAllowedDomains());

        List<String> returned = policy.getAllowedDomains();
        returned.add("c.example.com");
        assertEquals(Arrays.asList("a.example.com"), policy.getAllowedDomains());
    }

    @Test
    public void rejectsInvalidNetworkPolicy() {
        SandboxPolicy policy = new SandboxPolicy();
        policy.setNetworkPolicy("sometimes");
        assertInvalid(policy, "networkPolicy");
    }

    @Test
    public void rejectsInvalidCpus() {
        SandboxPolicy policy = new SandboxPolicy();
        policy.setCpus("two");
        assertInvalid(policy, "cpus");
    }

    @Test
    public void rejectsInvalidMemory() {
        SandboxPolicy policy = new SandboxPolicy();
        policy.setMemory("lots");
        assertInvalid(policy, "memory");
    }

    @Test
    public void rejectsCommaInDomain() {
        SandboxPolicy policy = new SandboxPolicy();
        policy.setAllowedDomains(Arrays.asList("a.example.com,b.example.com"));
        assertInvalid(policy, "comma");
    }

    @Test
    public void serializationRoundTrip() throws Exception {
        SandboxPolicy policy = new SandboxPolicy();
        policy.setNetworkPolicy("deny-all");
        policy.setAllowedDomains(Arrays.asList("a.example.com", "b.example.com"));
        policy.setCpus("2");
        policy.setMemory("4g");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(policy);
        }
        SandboxPolicy copy;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            copy = (SandboxPolicy) in.readObject();
        }
        assertNotSame(policy, copy);
        assertEquals(policy, copy);
        assertEquals(policy.hashCode(), copy.hashCode());
    }

    private static void assertInvalid(SandboxPolicy policy, String expectedMessagePart) {
        try {
            policy.validate();
            fail("expected validation to fail");
        } catch (IllegalArgumentException x) {
            if (!x.getMessage().contains(expectedMessagePart)) {
                fail("expected message to contain '" + expectedMessagePart + "', got: " + x.getMessage());
            }
        }
    }
}
