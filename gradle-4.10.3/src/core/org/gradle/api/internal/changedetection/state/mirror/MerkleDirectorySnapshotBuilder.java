/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.changedetection.state.mirror;

import org.gradle.caching.internal.BuildCacheHasher;
import org.gradle.caching.internal.DefaultBuildCacheHasher;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.hash.Hashing;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

public class MerkleDirectorySnapshotBuilder implements PhysicalSnapshotVisitor {
    private static final HashCode DIR_SIGNATURE = Hashing.md5().hashString("DIR");

    private final RelativePathSegmentsTracker relativePathSegmentsTracker = new RelativePathSegmentsTracker();
    private final Deque<List<PhysicalSnapshot>> levelHolder = new ArrayDeque<List<PhysicalSnapshot>>();
    private final Deque<String> directoryAbsolutePaths = new ArrayDeque<String>();
    private final boolean sortingRequired;
    private PhysicalSnapshot result;

    public static MerkleDirectorySnapshotBuilder sortingRequired() {
        return new MerkleDirectorySnapshotBuilder(true);
    }

    public static MerkleDirectorySnapshotBuilder noSortingRequired() {
        return new MerkleDirectorySnapshotBuilder(false);
    }

    private MerkleDirectorySnapshotBuilder(boolean sortingRequired) {
        this.sortingRequired = sortingRequired;
    }

    public boolean preVisitDirectory(String absolutePath, String name) {
        relativePathSegmentsTracker.enter(name);
        levelHolder.addLast(new ArrayList<PhysicalSnapshot>());
        directoryAbsolutePaths.addLast(absolutePath);
        return true;
    }

    @Override
    public boolean preVisitDirectory(PhysicalDirectorySnapshot directorySnapshot) {
        return preVisitDirectory(directorySnapshot.getAbsolutePath(), directorySnapshot.getName());
    }

    @Override
    public void visit(PhysicalSnapshot fileSnapshot) {
        if (relativePathSegmentsTracker.isRoot()) {
            result = fileSnapshot;
        } else {
            levelHolder.peekLast().add(fileSnapshot);
        }
    }

    @Override
    public void postVisitDirectory(PhysicalDirectorySnapshot directorySnapshot) {
        postVisitDirectory(true);
    }

    public void postVisitDirectory() {
        postVisitDirectory(true);
    }

    public boolean postVisitDirectory(boolean includeEmpty) {
        String name = relativePathSegmentsTracker.leave();
        List<PhysicalSnapshot> children = levelHolder.removeLast();
        String absolutePath = directoryAbsolutePaths.removeLast();
        if (children.isEmpty() && !includeEmpty) {
            return false;
        }
        if (sortingRequired) {
            Collections.sort(children, PhysicalSnapshot.BY_NAME);
        }
        BuildCacheHasher hasher = new DefaultBuildCacheHasher();
        hasher.putHash(DIR_SIGNATURE);
        for (PhysicalSnapshot child : children) {
            hasher.putString(child.getName());
            hasher.putHash(child.getHash());
        }
        PhysicalDirectorySnapshot directorySnapshot = new PhysicalDirectorySnapshot(absolutePath, name, children, hasher.hash());
        List<PhysicalSnapshot> siblings = levelHolder.peekLast();
        if (siblings != null) {
            siblings.add(directorySnapshot);
        } else {
            result = directorySnapshot;
        }
        return true;
    }

    public boolean isRoot() {
        return relativePathSegmentsTracker.isRoot();
    }

    public Iterable<String> getRelativePath() {
        return relativePathSegmentsTracker.getRelativePath();
    }

    @Nullable
    public PhysicalSnapshot getResult() {
        return result;
    }
}
