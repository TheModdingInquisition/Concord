/*
 * Concord - Copyright (c) 2020-2022 SciWhiz12
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package tk.sciwhiz12.concord;

import net.minecraftforge.common.util.Lazy;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.google.common.base.Verify.verify;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toUnmodifiableMap;
import static tk.sciwhiz12.concord.util.LambdaUtil.uncheck;

/*
 * The protocol version for Concord's channel is used to determine whether a Forge server will consider a Forge
 * client to be 'vanilla-like' for the purposes of fonts, eager translation, and other such features.
 *
 * The protocol version is divided into feature parts by the '-' (hyphen) separator. Each part is the version for a
 * specific feature; this partitions changes between features such that an update to one feature (for example, the
 * addition or removal of a translation key) do not necessarily affect other related features.
 *
 * There is always at least one feature part: the first part, which forms the 'root' feature version. If this
 * version is not compatible with the client version (see later), then all other features are considered
 * incompatible as well.
 *
 * A receiver must accept any number of feature parts, even if the count exceeds the amount of features known to the
 * receiver. This allows forwards compatibility with newer mod versions which have new features tracked in the
 * protocol version.
 *
 * A feature version is of the format "x.y[-w]", where x, y are whole numbers and an optional w text component. The
 * conventions for each version component are as follows:
 * - the x is termed the "major version", and is incremented for major changes which necessitate breaking compatibility
 * with previous major versions. A major version is only compatible with the same-numbered major version.
 * - the y is termed the "minor version", and is incremented for feature removals which remain compatible
 * with previously released versions of the same major version. A minor version is compatible if the client's version is
 * equal or lower to the server's minor version.
 * - the z is termed the "patch version", and is incremented for feature additions which remain compatible with previously
 * releases versions of the same major and minor version. A patch version is compatible with all other patch versions.
 */
public final class CompatibilityVersion {
    private static final Lazy<CompatibilityVersion> CURRENT = Lazy.of(() -> new CompatibilityVersion(
            Arrays.stream(FeatureVersion.values()).collect(toUnmodifiableMap(identity(), FeatureVersion::currentVersion))
    ));
    private static final VersionRange UNBOUNDED_RANGE = uncheck(() -> VersionRange.createFromVersionSpec("[0,)"));

    public static final String VERSION_SEPARATOR = ";";

    public static CompatibilityVersion current() {
        return CURRENT.get();
    }

    public static CompatibilityVersion fromString(String version) {
        // Old Concord versions have a hardcoded version of "yes"; they're 1.0.0
        if ("yes".equals(version)) {
            version = "1.0.0;1.0.0;1.0.0";
        }

        final List<String> versions = Arrays.asList(version.split(VERSION_SEPARATOR));
        if (!FMLEnvironment.production) {
            verify(versions.size() == 0, "Missing root version in version string %s", version);
        }

        final EnumMap<FeatureVersion, ArtifactVersion> parsedVersions = new EnumMap<>(FeatureVersion.class);

        final FeatureVersion[] featureVersions = FeatureVersion.values();
        for (int i = 0; i < featureVersions.length; i++) {
            if (versions.size() <= i) break;

            final FeatureVersion featureVersion = featureVersions[i];
            final String versionString = versions.get(i);

            parsedVersions.put(featureVersion, new DefaultArtifactVersion(versionString));
        }

        return new CompatibilityVersion(parsedVersions);
    }

    public static boolean currentCompatible(@Nullable String other, FeatureVersion version, VersionRange acceptableRange) {
        if (other == null || other.isBlank()) return false;
        final CompatibilityVersion otherVersion = CompatibilityVersion.fromString(other);
        return currentCompatible(otherVersion, version, acceptableRange);
    }

    public static boolean currentCompatible(@Nullable CompatibilityVersion other, FeatureVersion version, VersionRange acceptableRange) {
        if (other == null) return false;
        return CURRENT.get().isCompatible(other, version, acceptableRange);
    }

    private final Map<FeatureVersion, ArtifactVersion> versions;

    private CompatibilityVersion(Map<FeatureVersion, ArtifactVersion> versions) {
        this.versions = Map.copyOf(versions);
    }

    @Nullable
    private ArtifactVersion get(FeatureVersion key) {
        return versions.get(key);
    }

    public boolean isCompatible(CompatibilityVersion other, FeatureVersion version) {
        return isCompatible(other, version, UNBOUNDED_RANGE);
    }

    public boolean isCompatible(CompatibilityVersion other, FeatureVersion version, VersionRange acceptableRange) {
        // Root feature's major versions must be compatible; don't check the minor version
        return isCompatibleRaw(other, FeatureVersion.ROOT, UNBOUNDED_RANGE, false)
                && isCompatibleRaw(other, version, acceptableRange, true);
    }

    private boolean isCompatibleRaw(CompatibilityVersion other, FeatureVersion version, VersionRange acceptableRange, boolean checkMinorVersion) {
        @Nullable final ArtifactVersion ourVersion = this.get(version);
        @Nullable final ArtifactVersion theirVersion = other.get(version);
        // If we don't have that feature nor does the other side, then incompatible
        if (ourVersion == null || theirVersion == null) return false;

        // Differing major versions means not compatible
        if (ourVersion.getMajorVersion() != theirVersion.getMajorVersion()) {
            return false;
        }

        if (!FMLEnvironment.production) {
            verify(acceptableRange.containsVersion(ourVersion),
                    "Acceptable version range %s is not compatible with our own version %s",
                    acceptableRange, ourVersion);
        }

        // Their version must be acceptable in our version range
        if (!acceptableRange.containsVersion(theirVersion)) {
            return false;
        }

        // If we are checking minor versions and the minor versions are mismatched, say we're not compatible.
        // Otherwise, we assume compatible (regardless of the patch version)
        return !checkMinorVersion || ourVersion.getMinorVersion() == theirVersion.getMinorVersion();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CompatibilityVersion that = (CompatibilityVersion) o;
        return versions.equals(that.versions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(versions);
    }

    @Override
    public String toString() {
        return versions.values().stream().map(Object::toString).collect(Collectors.joining(VERSION_SEPARATOR));
    }
}
