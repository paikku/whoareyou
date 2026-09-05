package com.carcast.server;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class CgroupEscapeTest {
    @Test
    public void v2NearestAncestorFirstThenRoot() {
        List<String> c = CgroupEscape.candidates(Arrays.asList("0::/uid_0/pid_1234"), 2000);
        assertEquals(Arrays.asList(
                "/sys/fs/cgroup/uid_2000/cgroup.procs",
                "/sys/fs/cgroup/uid_0/cgroup.procs",
                "/sys/fs/cgroup/cgroup.procs"), c);
    }

    @Test
    public void v1HierarchiesMapToAndroidMounts() {
        List<String> c = CgroupEscape.candidates(Arrays.asList("3:cpuacct:/uid_0/pid_7", "2:cpuset:/", "1:name=systemd:/x"), 2000);
        assertEquals(Arrays.asList(
                "/acct/uid_2000/cgroup.procs", "/acct/uid_0/cgroup.procs", "/acct/cgroup.procs",
                "/dev/cpuset/uid_2000/cgroup.procs"), c);
    }

    @Test
    public void rootGroupOnlyOffersSiblingUidGroup() {
        assertEquals(Arrays.asList("/sys/fs/cgroup/uid_2000/cgroup.procs"), CgroupEscape.candidates(Arrays.asList("0::/"), 2000));
    }
}
