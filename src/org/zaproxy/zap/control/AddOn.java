/*
 * Zed Attack Proxy (ZAP) and its related class files.
 * 
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 * 
 * Copyright 2012 ZAP development team
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0 
 *   
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License. 
 */
package org.zaproxy.zap.control;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.SystemUtils;
import org.apache.log4j.Logger;
import org.jgrapht.DirectedGraph;
import org.jgrapht.alg.CycleDetector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.traverse.TopologicalOrderIterator;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.extension.Extension;
import org.zaproxy.zap.Version;
import org.zaproxy.zap.control.BaseZapAddOnXmlData.AddOnDep;
import org.zaproxy.zap.control.BaseZapAddOnXmlData.Dependencies;

public class AddOn  {
	public enum Status {unknown, example, alpha, beta, weekly, release}
	
	/**
	 * The installation status of the add-on.
	 * 
	 * @since 2.4.0
	 */
	public enum InstallationStatus {

		/**
		 * The add-on is available for installation, for example, an add-on in the marketplace (even if it requires previous
		 * actions, in this case, download the file).
		 */
		AVAILABLE,

		/**
		 * The add-on was not (yet) installed. For example, the add-on is available in the 'plugin' directory but it's missing a
		 * dependency or requires a greater Java version. It's also in this status while a dependency is being updated.
		 */
		NOT_INSTALLED,

		/**
		 * The add-on is installed.
		 */
		INSTALLED,

		/**
		 * The add-on is being downloaded.
		 */
		DOWNLOADING,

		/**
		 * The uninstallation of the add-on failed. For example, when the add-on is not dynamically installable or when an
		 * {@code Exception} is thrown during the uninstallation.
		 */
		UNINSTALLATION_FAILED,

		/**
		 * The soft uninstallation of the add-on failed. It's in this status when the uninstallation failed for an update of a
		 * dependency.
		 */
		SOFT_UNINSTALLATION_FAILED
	}
	
	private String id;
	private String name;
	private String description = "";
	private String author = "";
	private int fileVersion;
	private Version version;

	private Status status;
	private String changes = "";
	private File file = null;
	private URL url = null;
	private URL info = null;
	private long size = 0;
	private boolean hasZapAddOnEntry = false;

	/**
	 * Flag that indicates if the manifest was read (or attempted to). Allows to prevent reading the manifest a second time when
	 * the add-on file is corrupt.
	 */
	private boolean manifestRead;

	private String notBeforeVersion = null;
	private String notFromVersion = null;
	private String hash = null;

	/**
	 * The installation status of the add-on.
	 * <p>
	 * Default is {@code NOT_INSTALLED}.
	 * 
	 * @see InstallationStatus#NOT_INSTALLED
	 */
	private InstallationStatus installationStatus = InstallationStatus.NOT_INSTALLED;
	
	private List<String> extensions = Collections.emptyList();

	/**
	 * The extensions of the add-on that were loaded.
	 * <p>
	 * This instance variable is lazy initialised.
	 * 
	 * @see #setLoadedExtensions(List)
	 * @see #addLoadedExtension(Extension)
	 */
	private List<Extension> loadedExtensions;
	private List<String> ascanrules = Collections.emptyList();
	private List<String> pscanrules = Collections.emptyList();
	private List<String> files = Collections.emptyList();
	
	private Dependencies dependencies;

	private static final Logger logger = Logger.getLogger(AddOn.class);
	
	public static boolean isAddOn(String fileName) {
		if (! fileName.toLowerCase().endsWith(".zap")) {
			return false;
		}
		if (fileName.substring(0, fileName.indexOf(".")).split("-").length < 3) {
			return false;
		}
		String[] strArray = fileName.substring(0, fileName.indexOf(".")).split("-");
		try {
			Status.valueOf(strArray[1]);
			Integer.parseInt(strArray[2]);
		} catch (Exception e) {
			return false;
		}

		return true;
		
	}
	public static boolean isAddOn(File f) {
		if (! f.exists()) {
			return false;
		}
		return isAddOn(f.getName());
	}

	public AddOn(String fileName) throws Exception {
		// Format is <name>-<status>-<version>.zap
		if (! isAddOn(fileName)) {
			throw new Exception("Invalid ZAP add-on file " + fileName);
		}
		String[] strArray = fileName.substring(0, fileName.indexOf(".")).split("-");
		this.id = strArray[0];
		this.name = this.id;	// Will be overriden if theres a ZapAddOn.xml file
		this.status = Status.valueOf(strArray[1]);
		this.fileVersion = Integer.parseInt(strArray[2]);
	}

	/**
	 * Constructs an {@code AddOn} from the given {@code file}.
	 * <p>
	 * The {@code ZapAddOn.xml} ZIP file entry is read after validating that the add-on has a valid add-on file name.
	 * <p>
	 * The installation status of the add-on is 'not installed'.
	 * 
	 * @param file the file of the add-on
	 * @throws Exception if the given {@code file} does not exist, does not have a valid add-on file name or an error occurred
	 *			 while reading the {@code ZapAddOn.xml} ZIP file entry
	 */
	public AddOn(File file) throws Exception {
		this(file.getName());
		if (! isAddOn(file)) {
			throw new Exception("Invalid ZAP add-on file " + file.getAbsolutePath());
		}
		this.file = file;
		loadManifestFile();
	}
	
	private void loadManifestFile() throws IOException {
		manifestRead = true;
		if (file.exists()) {
			// Might not exist in the tests
			try (ZipFile zip = new ZipFile(file)) {
				ZipEntry zapAddOnEntry = zip.getEntry("ZapAddOn.xml");
				if (zapAddOnEntry != null) {
					try (InputStream zis = zip.getInputStream(zapAddOnEntry)) {
						ZapAddOnXmlFile zapAddOnXml = new ZapAddOnXmlFile(zis);

						this.name = zapAddOnXml.getName();
						this.version = zapAddOnXml.getVersion();
						this.description = zapAddOnXml.getDescription();
						this.changes = zapAddOnXml.getChanges();
						this.author = zapAddOnXml.getAuthor();
						this.notBeforeVersion = zapAddOnXml.getNotBeforeVersion();
						this.notFromVersion = zapAddOnXml.getNotFromVersion();
						this.dependencies = zapAddOnXml.getDependencies();

						this.ascanrules = zapAddOnXml.getAscanrules();
						this.extensions = zapAddOnXml.getExtensions();
						this.files = zapAddOnXml.getFiles();
						this.pscanrules = zapAddOnXml.getPscanrules();

						hasZapAddOnEntry = true;
					}

				}
			}
		}
		
	}

	/**
	 * Constructs an {@code AddOn} from an add-on entry of {@code ZapVersions.xml} file. The installation status of the add-on
	 * is 'not installed'.
	 * <p>
	 * The given {@code SubnodeConfiguration} must have a {@code XPathExpressionEngine} installed.
	 * <p>
	 * The {@code ZapAddOn.xml} ZIP file entry is read, if the add-on file exists locally.
	 * 
	 * @param id the id of the add-on
	 * @param baseDir the base directory where the add-on is located
	 * @param xmlData the source of add-on entry of {@code ZapVersions.xml} file
	 * @throws MalformedURLException if the {@code URL} of the add-on is malformed
	 * @throws IOException if an error occurs while reading the XML data
	 * @see org.apache.commons.configuration.tree.xpath.XPathExpressionEngine
	 */
	public AddOn(String id, File baseDir, SubnodeConfiguration xmlData) throws MalformedURLException, IOException {
		this.id = id;
		ZapVersionsAddOnEntry addOnData = new ZapVersionsAddOnEntry(xmlData);
		this.name = addOnData.getName();
		this.description = addOnData.getDescription();
		this.author = addOnData.getAuthor();
		this.fileVersion = addOnData.getPackageVersion();
		this.dependencies = addOnData.getDependencies();
		this.version = addOnData.getVersion();
		this.status = AddOn.Status.valueOf(addOnData.getStatus());
		this.changes = addOnData.getChanges();
		this.url = new URL(addOnData.getUrl());
		this.file = new File(baseDir, addOnData.getFile());
		this.size = addOnData.getSize();
		this.notBeforeVersion = addOnData.getNotBeforeVersion();
		this.notFromVersion = addOnData.getNotFromVersion();
		if (addOnData.getInfo() != null && !addOnData.getInfo().isEmpty()) {
			try {
				this.info = new URL(addOnData.getInfo());
			} catch (Exception ignore) {
				if (logger.isDebugEnabled()) {
					logger.debug("Wrong info URL for add-on \"" + name + "\":", ignore);
				}
			}
		}
		this.hash = addOnData.getHash();
		
		loadManifestFile();
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return description;
	}
	
	public void setDescription(String description) {
		this.description = description;
	}
	
	public int getFileVersion() {
		return fileVersion;
	}

	/**
	 * Gets the semantic version of this add-on.
	 *
	 * @return the semantic version of the add-on, or {@code null} if none
	 * @since 2.4.0
	 */
	public Version getVersion() {
		return version;
	}

	public Status getStatus() {
		return status;
	}

	public void setStatus(Status status) {
		this.status = status;
	}

	public String getChanges() {
		return changes;
	}

	public void setChanges(String changes) {
		this.changes = changes;
	}

	public File getFile() {
		return file;
	}

	public void setFile(File file) {
		this.file = file;
	}
	
	public URL getUrl() {
		return url;
	}

	public void setUrl(URL url) {
		this.url = url;
	}

	public long getSize() {
		return size;
	}

	public void setSize(long size) {
		this.size = size;
	}

	public String getAuthor() {
		return author;
	}
	
	public void setAuthor(String author) {
		this.author = author;
	}

	/**
	 * Sets the installation status of the add-on.
	 *
	 * @param installationStatus the new installation status
	 * @throws IllegalArgumentException if the given {@code installationStatus} is {@code null}.
	 * @since 2.4.0
	 */
	public void setInstallationStatus(InstallationStatus installationStatus) {
		if (installationStatus == null) {
			throw new IllegalArgumentException("Parameter installationStatus must not be null.");
		}

		this.installationStatus = installationStatus;
	}

	/**
	 * Gets installations status of the add-on.
	 *
	 * @return the installation status, never {@code null}
	 * @since 2.4.0
	 */
	public InstallationStatus getInstallationStatus() {
		return installationStatus;
	}
	
	public boolean hasZapAddOnEntry() {
		if (! hasZapAddOnEntry) {
			if (!manifestRead) {
				// Worth trying, as it depends which constructor has been used
				try {
					this.loadManifestFile();
				} catch (IOException e) {
					if (logger.isDebugEnabled()) {
						logger.debug("Failed to read the ZapAddOn.xml file of " + id + ":", e);
					}
				}
			}
		}
		return hasZapAddOnEntry;
	}
	
	public List<String> getExtensions() {
		return extensions;
	}

	/**
	 * Gets the extensions of this add-on that were loaded.
	 *
	 * @return an unmodifiable {@code List} with the extensions of this add-on that were loaded
	 * @since 2.4.0
	 */
	public List<Extension> getLoadedExtensions() {
		if (loadedExtensions == null) {
			return Collections.emptyList();
		}
		return Collections.unmodifiableList(loadedExtensions);
	}

	/**
	 * Sets the loaded extensions of this add-on to the given list of extensions.
	 * <p>
	 * It's made a copy of the given list.
	 * <p>
	 * This add-on is set to the given {@code extensions}.
	 *
	 * @param extensions the extensions of this add-on that were loaded
	 * @throws IllegalArgumentException if extensions is {@code null}
	 * @since 2.4.0
	 * @see Extension#setAddOn(AddOn)
	 */
	public void setLoadedExtensions(List<Extension> extensions) {
		if (extensions == null) {
			throw new IllegalArgumentException("Parameter extensions must not be null.");
		}

		if (loadedExtensions != null) {
			for (Extension extension : loadedExtensions) {
				extension.setAddOn(null);
			}
		}

		loadedExtensions = new ArrayList<>(extensions);
		for (Extension extension : loadedExtensions) {
			extension.setAddOn(this);
		}
	}

	/**
	 * Adds the given {@code extension} to the list of loaded extensions of this add-on.
	 * <p>
	 * This add-on is set to the given {@code extension}.
	 *
	 * @param extension the extension of this add-on that was loaded
	 * @since 2.4.0
	 * @see Extension#setAddOn(AddOn)
	 */
	public void addLoadedExtension(Extension extension) {
		if (loadedExtensions == null) {
			loadedExtensions = new ArrayList<>(1);
		}

		if (!loadedExtensions.contains(extension)) {
			loadedExtensions.add(extension);
			extension.setAddOn(this);
		}
	}
	
	public List<String> getAscanrules() {
		return ascanrules;
	}
	
	public List<String> getPscanrules() {
		return pscanrules;
	}
	
	public List<String> getFiles() {
		return files;
	}
	
	public boolean isSameAddOn(AddOn addOn) {
		return this.getId().equals(addOn.getId());
	}

	public boolean isUpdateTo(AddOn addOn) throws IllegalArgumentException {
		if (! this.isSameAddOn(addOn)) {
			throw new IllegalArgumentException("Different addons: " + this.getId() + " != " + addOn.getId());
		}
		if (this.getFileVersion() > addOn.getFileVersion()) {
			return true;
		}
		return this.getStatus().ordinal() > addOn.getStatus().ordinal();
	}
	
	/**
	 * @deprecated (2.4.0) Use {@link #calculateRunRequirements(Collection)} instead. Returns {@code false}.
	 */
	@Deprecated
	@SuppressWarnings("javadoc")
	public boolean canLoad() {
		return false;
	}

	/**
	 * Tells whether or not this add-on can be loaded in the currently running ZAP version, as given by
	 * {@code Constant.PROGRAM_VERSION}.
	 *
	 * @return {@code true} if the add-on can be loaded in the currently running ZAP version, {@code false} otherwise
	 * @see #canLoadInVersion(String)
	 * @see Constant#PROGRAM_VERSION
	 */
	public boolean canLoadInCurrentVersion() {
		return canLoadInVersion(Constant.PROGRAM_VERSION);
	}

	/**
	 * Tells whether or not this add-on can be run in the currently running Java version.
	 * <p>
	 * This is a convenience method that calls {@code canRunInJavaVersion(String)} with the running Java version (as given by
	 * {@code SystemUtils.JAVA_VERSION}) as parameter.
	 * 
	 * @return {@code true} if the add-on can be run in the currently running Java version, {@code false} otherwise
	 * @since 2.4.0
	 * @see #canRunInJavaVersion(String)
	 * @see SystemUtils#JAVA_VERSION
	 */
	public boolean canRunInCurrentJavaVersion() {
		return canRunInJavaVersion(SystemUtils.JAVA_VERSION);
	}

	/**
	 * Tells whether or not this add-on can be run in the given {@code javaVersion}.
	 * <p>
	 * If the given {@code javaVersion} is {@code null} and this add-on depends on a specific java version the method returns
	 * {@code false}.
	 * 
	 * @param javaVersion the java version that will be checked
	 * @return {@code true} if the add-on can be loaded in the given {@code javaVersion}, {@code false} otherwise
	 * @since 2.4.0
	 */
	public boolean canRunInJavaVersion(String javaVersion) {
		if (dependencies == null) {
			return true;
		}

		String requiredVersion = dependencies.getJavaVersion();
		if (requiredVersion == null) {
			return true;
		}

		if (javaVersion == null) {
			return false;
		}

		return getJavaVersion(javaVersion) >= getJavaVersion(requiredVersion);
	}

	/**
	 * Calculates the requirements to run this add-on, in the current ZAP and Java versions and with the given
	 * {@code availableAddOns}.
	 * <p>
	 * If the add-on depends on other add-ons, those add-ons are also checked if are also runnable.
	 * <p>
	 * <strong>Note:</strong> All the given {@code availableAddOns} are expected to be loadable in the currently running ZAP
	 * version, that is, the method {@code AddOn.canLoadInCurrentVersion()}, returns {@code true}.
	 * 
	 * @param availableAddOns the other add-ons available
	 * @return a requirements to run the add-on, and if not runnable the reason why it's not.
	 * @since 2.4.0
	 * @see #canLoadInCurrentVersion()
	 * @see #canRunInCurrentJavaVersion()
	 * @see RunRequirements
	 */
	public RunRequirements calculateRunRequirements(Collection<AddOn> availableAddOns) {
		return calculateRunRequirementsImpl(availableAddOns, new RunRequirements(), null, this);
	}

	private static RunRequirements calculateRunRequirementsImpl(
			Collection<AddOn> availableAddOns,
			RunRequirements requirements,
			AddOn parent,
			AddOn addOn) {

		AddOn installedVersion = getAddOn(availableAddOns, addOn.getId());
		if (installedVersion != null && !addOn.equals(installedVersion)) {
			requirements.setRunnable(false);
			requirements.setIssue(RunRequirements.DependencyIssue.OLDER_VERSION, installedVersion);
			if (logger.isDebugEnabled()) {
				logger.debug("Add-on " + addOn + " not runnable, old version still installed: " + installedVersion);
			}
			return requirements;
		}

		if (!requirements.addDependency(parent, addOn)) {
			requirements.setRunnable(false);
			logger.warn("Cyclic dependency detected with: " + requirements.getDependencies());
			requirements.setIssue(RunRequirements.DependencyIssue.CYCLIC, requirements.getDependencies());
			return requirements;
		}

		if (addOn.dependencies == null) {
			return requirements;
		}

		if (!addOn.canRunInCurrentJavaVersion()) {
			requirements.setRunnable(false);
			requirements.setMinimumJavaVersionIssue(addOn, addOn.dependencies.getJavaVersion());
		}

		for (AddOnDep dependency : addOn.dependencies.getAddOns()) {
			String addOnId = dependency.getId();
			if (addOnId != null) {
				AddOn addOnDep = getAddOn(availableAddOns, addOnId);
				if (addOnDep == null) {
					requirements.setRunnable(false);
					requirements.setIssue(RunRequirements.DependencyIssue.MISSING, addOnId);
					return requirements;
				}

				if (dependency.getNotBeforeVersion() > -1 && addOnDep.fileVersion < dependency.getNotBeforeVersion()) {
					requirements.setRunnable(false);
					requirements.setIssue(
							RunRequirements.DependencyIssue.PACKAGE_VERSION_NOT_BEFORE,
							addOnDep,
							Integer.valueOf(dependency.getNotBeforeVersion()));
					return requirements;
				}

				if (dependency.getNotFromVersion() > -1 && addOnDep.fileVersion > dependency.getNotFromVersion()) {
					requirements.setRunnable(false);
					requirements.setIssue(
							RunRequirements.DependencyIssue.PACKAGE_VERSION_NOT_FROM,
							addOnDep,
							Integer.valueOf(dependency.getNotFromVersion()));
					return requirements;
				}

				if (!dependency.getSemVer().isEmpty()) {
					if (addOnDep.version == null || !addOnDep.version.matches(dependency.getSemVer())) {
						requirements.setRunnable(false);
						requirements.setIssue(RunRequirements.DependencyIssue.VERSION, addOnDep, dependency.getSemVer());
						return requirements;
					}
				}

				RunRequirements reqs = calculateRunRequirementsImpl(availableAddOns, requirements, addOn, addOnDep);
				if (reqs.hasDependencyIssue()) {
					return reqs;
				}
			}
		}

		return requirements;
	}

	/**
	 * Returns the minimum Java version required to run this add-on or an empty {@code String} if there's no minimum version.
	 *
	 * @return the minimum Java version required to run this add-on or an empty {@code String} if no minimum version
	 * @since 2.4.0
	 */
	public String getMinimumJavaVersion() {
		if (dependencies == null) {
			return "";
		}
		return dependencies.getJavaVersion();
	}

	/**
	 * Gets the add-on with the given {@code id} from the given collection of {@code addOns}.
	 *
	 * @param addOns the collection of add-ons where the search will be made
	 * @param id the id of the add-on to search for
	 * @return the {@code AddOn} with the given id, or {@code null} if not found
	 */
	private static AddOn getAddOn(Collection<AddOn> addOns, String id) {
		for (AddOn addOn : addOns) {
			if (addOn.getId().equals(id)) {
				return addOn;
			}
		}
		return null;
	}

	/**
	 * Tells whether or not this add-on can be loaded in the given {@code zapVersion}.
	 *
	 * @param zapVersion the ZAP version that will be checked
	 * @return {@code true} if the add-on can be loaded in the given {@code zapVersion}, {@code false} otherwise
	 */
	public boolean canLoadInVersion(String zapVersion) {
		ZapReleaseComparitor zrc = new ZapReleaseComparitor();
		ZapRelease zr = new ZapRelease(zapVersion);
		if (this.notBeforeVersion != null && this.notBeforeVersion.length() > 0) {
			ZapRelease notBeforeRelease = new ZapRelease(this.notBeforeVersion);
			if (zrc.compare(zr, notBeforeRelease) < 0) {
				return false;
			}
		}
		if (this.notFromVersion != null && this.notFromVersion.length() > 0) {
			ZapRelease notFromRelease = new ZapRelease(this.notFromVersion);
			return (zrc.compare(zr, notFromRelease) < 0);
		}
		return true;
	}
	
	public void setNotBeforeVersion(String notBeforeVersion) {
		this.notBeforeVersion = notBeforeVersion;
	}
	
	public void setNotFromVersion(String notFromVersion) {
		this.notFromVersion = notFromVersion;
	}
	
	public String getNotBeforeVersion() {
		return notBeforeVersion;
	}
	
	public String getNotFromVersion() {
		return notFromVersion;
	}

	public URL getInfo() {
		return info;
	}
	
	public void setInfo(URL info) {
		this.info = info;
	}
	
	public String getHash() {
		return hash;
	}
	
	/**
	 * Returns the IDs of the add-ons dependencies, an empty collection if none.
	 *
	 * @return the IDs of the dependencies.
	 * @since 2.4.0
	 */
	public List<String> getIdsAddOnDependencies() {
		if (dependencies == null) {
			return Collections.emptyList();
		}

		List<String> ids = new ArrayList<>(dependencies.getAddOns().size());
		for (AddOnDep dep : dependencies.getAddOns()) {
			ids.add(dep.getId());
		}
		return ids;
	}

	/**
	 * Tells whether or not this add-on has a (direct) dependency on the given {@code addOn} (including version).
	 *
	 * @param addOn the add-on that will be checked
	 * @return {@code true} if it depends on the given add-on, {@code false} otherwise.
	 * @since 2.4.0
	 */
	public boolean dependsOn(AddOn addOn) {
		if (dependencies == null || dependencies.getAddOns().isEmpty()) {
			return false;
		}

		for (AddOnDep dependency : dependencies.getAddOns()) {
			if (dependency.getId().equals(addOn.id)) {
				if (dependency.getNotBeforeVersion() > -1 && addOn.fileVersion < dependency.getNotBeforeVersion()) {
					return false;
				}

				if (dependency.getNotFromVersion() > -1 && addOn.fileVersion > dependency.getNotFromVersion()) {
					return false;
				}

				if (!dependency.getSemVer().isEmpty()) {
					if (addOn.version == null) {
						return false;
					} else if (!addOn.version.matches(dependency.getSemVer())) {
						return false;
					}
				}
				return true;
			}
		}

		return false;
	}

	/**
	 * Tells whether or not this add-on has a (direct) dependency on any of the given {@code addOns} (including version).
	 *
	 * @param addOns the add-ons that will be checked
	 * @return {@code true} if it depends on any of the given add-ons, {@code false} otherwise.
	 * @since 2.4.0
	 */
	public boolean dependsOn(Collection<AddOn> addOns) {
		if (dependencies == null || dependencies.getAddOns().isEmpty()) {
			return false;
		}

		for (AddOn addOn : addOns) {
			if (dependsOn(addOn)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public String toString() {
		StringBuilder strBuilder = new StringBuilder();
		strBuilder.append("[id=").append(id);
		strBuilder.append(", fileVersion=").append(fileVersion);
		if (version != null) {
			strBuilder.append(", version=").append(version);
		}
		strBuilder.append(']');

		return strBuilder.toString();
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((id == null) ? 0 : id.hashCode());
		result = prime * result + fileVersion;
		result = prime * result + ((version == null) ? 0 : version.hashCode());
		return result;
	}

	/**
	 * Two add-ons are considered equal if both add-ons have the same ID, file version and semantic version.
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		AddOn other = (AddOn) obj;
		if (id == null) {
			if (other.id != null) {
				return false;
			}
		} else if (!id.equals(other.id)) {
			return false;
		}
		if (fileVersion != other.fileVersion) {
			return false;
		}
		if (version == null) {
			if (other.version != null) {
				return false;
			}
		} else if (!version.equals(other.version)) {
			return false;
		}
		return true;
	}

	/**
	 * The requirements to run an {@code AddOn}.
	 * <p>
	 * It can be used to check if an add-on can or not be run, which requirements it has (for example, minimum Java version or
	 * dependency add-ons) and which issues prevent it from being run, if any.
	 * 
	 * @since 2.4.0
	 */
	public static class RunRequirements {

		/**
		 * The reason why an add-on can not be run because of a dependency.
		 * <p>
		 * More details of the issue can be obtained with the method {@code RunRequirements.getDependencyIssueDetails()}. The
		 * exact contents are mentioned in each {@code DependencyIssue} constant.
		 * 
		 * @see RunRequirements#getDependencyIssueDetails()
		 */
		public enum DependencyIssue {

			/**
			 * A cyclic dependency was detected.
			 * <p>
			 * Issue details contain all the add-ons in the cyclic chain.
			 */
			CYCLIC,

			/**
			 * Older version of the add-on is still installed.
			 * <p>
			 * Issue details contain the old version.
			 */
			OLDER_VERSION,

			/**
			 * A dependency was not found.
			 * <p>
			 * Issue details contain the id of the add-on.
			 */
			MISSING,

			/**
			 * The dependency found has a older version than the version required.
			 * <p>
			 * Issue details contain the instance of the {@code AddOn} and the required version.
			 */
			PACKAGE_VERSION_NOT_BEFORE,

			/**
			 * The dependency found has a newer version than the version required.
			 * <p>
			 * Issue details contain the instance of the {@code AddOn} and the required version.
			 */
			PACKAGE_VERSION_NOT_FROM,

			/**
			 * The dependency found has a different semantic version.
			 * <p>
			 * Issue details contain the instance of the {@code AddOn} and the required version.
			 */
			VERSION
		}

		private final DirectedGraph<AddOn, DefaultEdge> dependencyTree;
		private AddOn addOn;
		private Set<AddOn> dependencies;
		
		private DependencyIssue depIssue;
		private List<Object> issueDetails;

		private String minimumJavaVersion;
		private AddOn addOnMinimumJavaVersion;

		private boolean runnable;

		protected RunRequirements() {
			dependencyTree = new DefaultDirectedGraph<>(DefaultEdge.class);
			runnable = true;
			issueDetails = Collections.emptyList();
		}

		/**
		 * Gets the add-on that was tested to check if it can be run.
		 *
		 * @return the tested add-on
		 */
		public AddOn getAddOn() {
			return addOn;
		}

		/**
		 * Tells whether or not this add-on has a dependency issue.
		 *
		 * @return {@code true} if the add-on has a dependency issue, {@code false} otherwise
		 * @see #getDependencyIssue()
		 * @see #getDependencyIssueDetails()
		 * @see DependencyIssue
		 */
		public boolean hasDependencyIssue() {
			return (depIssue != null);
		}

		/**
		 * Gets the dependency issue, if any.
		 *
		 * @return the {@code DependencyIssue} or {@code null}, if none
		 * @see #hasDependencyIssue()
		 * @see #getDependencyIssueDetails()
		 * @see DependencyIssue
		 */
		public DependencyIssue getDependencyIssue() {
			return depIssue;
		}

		/**
		 * Gets the details of the dependency issue, if any.
		 *
		 * @return a list containing the details of the issue or an empty list if none
		 * @see #hasDependencyIssue()
		 * @see #getDependencyIssue()
		 * @see DependencyIssue
		 */
		public List<Object> getDependencyIssueDetails() {
			return issueDetails;
		}

		/**
		 * Tells whether or not this add-on requires a newer Java version to run.
		 * <p>
		 * The requirement might be imposed by a dependency or the add-on itself. To check which one use the methods
		 * {@code getAddOn()} and {@code getAddOnMinimumJavaVersion()}.
		 *
		 * @return {@code true} if the add-on requires a newer Java version, {@code false} otherwise.
		 * @see #getAddOn()
		 * @see #getAddOnMinimumJavaVersion()
		 * @see #getMinimumJavaVersion()
		 */
		public boolean isNewerJavaVersionRequired() {
			return (minimumJavaVersion != null);
		}

		/**
		 * Gets the minimum Java version required to run the add-on.
		 *
		 * @return the Java version, or {@code null} if no minimum.
		 * @see #isNewerJavaVersionRequired()
		 * @see #getAddOn()
		 * @see #getAddOnMinimumJavaVersion()
		 */
		public String getMinimumJavaVersion() {
			return minimumJavaVersion;
		}

		/**
		 * Gets the add-on that requires the minimum Java version.
		 *
		 * @return the add-on, or {@code null} if no minimum.
		 * @see #isNewerJavaVersionRequired()
		 * @see #getMinimumJavaVersion()
		 * @see #getAddOn()
		 */
		public AddOn getAddOnMinimumJavaVersion() {
			return addOnMinimumJavaVersion;
		}

		/**DirectedAcyclicGraph
		 * Tells whether or not this add-on can be run.
		 *
		 * @return {@code true} if the add-on can be run, {@code false} otherwise
		 */
		public boolean isRunnable() {
			return runnable;
		}

		/**
		 * Gets the (found) dependencies of the add-on, including transitive dependencies.
		 * 
		 * @return a set containing the dependencies of the add-on
		 * @see AddOn#getIdsAddOnDependencies()
		 */
		public Set<AddOn> getDependencies() {
			if (dependencies == null) {
				dependencies = new HashSet<>();
				for (TopologicalOrderIterator<AddOn, DefaultEdge> it = new TopologicalOrderIterator<>(dependencyTree); it.hasNext();) {
					dependencies.add(it.next());
				}
				dependencies.remove(addOn);
			}
			return Collections.unmodifiableSet(dependencies);
		}

		protected void setIssue(DependencyIssue issue, Object... details) {
			this.depIssue = issue;
			if (details != null) {
				issueDetails = Arrays.asList(details);
			} else {
				issueDetails = Collections.emptyList();
			}
		}

		protected void setMinimumJavaVersionIssue(AddOn srcAddOn, String requiredVersion) {
			if (minimumJavaVersion == null) {
				setMinimumJavaVersion(srcAddOn, requiredVersion);
			} else if (requiredVersion.compareTo(minimumJavaVersion) > 0) {
				setMinimumJavaVersion(srcAddOn, requiredVersion);
			}
		}

		private void setMinimumJavaVersion(AddOn srcAddOn, String requiredVersion) {
			addOnMinimumJavaVersion = srcAddOn;
			minimumJavaVersion = requiredVersion;
		}

		protected boolean addDependency(AddOn parent, AddOn addOn) {
			if (parent == null) {
				this.addOn = addOn;
				dependencyTree.addVertex(addOn);
				return true;
			}

			dependencyTree.addVertex(parent);
			dependencyTree.addVertex(addOn);

			dependencyTree.addEdge(parent, addOn);

			CycleDetector<AddOn, DefaultEdge> cycleDetector = new CycleDetector<>(dependencyTree);
			boolean cycle = cycleDetector.detectCycles();
			if (cycle) {
				dependencies = cycleDetector.findCycles();

				return false;
			}
			return true;
		}

		protected void setRunnable(boolean runnable) {
			this.runnable = runnable;
		}
	}

	private static int getJavaVersion(String javaVersion) {
		return toVersionInt(toJavaVersionIntArray(javaVersion, 2));
	}

    // NOTE: Following 2 methods copied from org.apache.commons.lang.SystemUtils version 2.6 because of constrained visibility
    private static int[] toJavaVersionIntArray(String version, int limit) {
        if (version == null) {
            return ArrayUtils.EMPTY_INT_ARRAY;
        }
        String[] strings = StringUtils.split(version, "._- ");
        int[] ints = new int[Math.min(limit, strings.length)];
        int j = 0;
        for (int i = 0; i < strings.length && j < limit; i++) {
            String s = strings[i];
            if (s.length() > 0) {
                try {
                    ints[j] = Integer.parseInt(s);
                    j++;
                } catch (Exception e) {
                }
            }
        }
        if (ints.length > j) {
            int[] newInts = new int[j];
            System.arraycopy(ints, 0, newInts, 0, j);
            ints = newInts;
        }
        return ints;
    }

    private static int toVersionInt(int[] javaVersions) {
        if (javaVersions == null) {
            return 0;
        }
        int intVersion = 0;
        int len = javaVersions.length;
        if (len >= 1) {
            intVersion = javaVersions[0] * 100;
        }
        if (len >= 2) {
            intVersion += javaVersions[1] * 10;
        }
        if (len >= 3) {
            intVersion += javaVersions[2];
        }
        return intVersion;
    }
}
