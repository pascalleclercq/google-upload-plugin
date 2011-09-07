package fr.opensagres.tools;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
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

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Iterator;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;

/**
 * Goal which touches a timestamp file.
 * 
 * @goal upload
 * 
 * @phase deploy
 */
public class GoogleCodeUploadMojo extends AbstractMojo {

	/**
	 * @parameter expression="${settings}"
	 * @required
	 * @readonly
	 */
	protected Settings settings;

	/**
	 * Google Code project name to upload to.
	 * 
	 * @parameter expression="${projectName}"
	 * @required
	 */
	private String projectName;

	/**
	 * The local path of the file to upload.
	 * 
	 * @parameter expression="${fileName}"
	 * 
	 */
	private String fileName;

	/**
	 * The file name that this file will be given on Google Code.
	 * 
	 * @parameter
	 * 
	 */
	private String targetFileName;

	/**
	 * Summary of the upload.
	 * 
	 * @parameter
	 * 
	 */
	private String summary;

	/**
	 * Overrides the default upload URL. This parameter is only useful for
	 * testing this Ant task without uploading to the live server.
	 */
	private String uploadUrl;

	/**
	 * If set to true, this task will print debugging information to System.out
	 * as it progresses through its job.
	 * 
	 * @parameter
	 */
	private boolean verbose;

	/**
	 * The labels that the download should have, separated by commas. Extra
	 * whitespace before and after each label name will not be considered part
	 * of the label name.
	 * 
	 * @parameter
	 */
	private String labels;

	/**
	 * In case of using https connection, some certificate could not be
	 * validated by ssl security layer. To disable the ssl security layer, set
	 * this parameter to true. Use this option only if you understand the risks.
	 * 
	 * @parameter
	 */
	private boolean ignoreSslCertificateHostname = false;

	/**
	 * Classifiers.
	 * 
	 * @parameter
	 */
	private String classifier;
	/**
	 * @parameter expression="${project}"
	 * @required
	 * @readonly
	 */
	protected MavenProject project;

	public void execute() throws MojoExecutionException {

		try {
			upload();
		} catch (Exception ex) {
			throw new MojoExecutionException(ex.getMessage(), ex);
		}
	}

	/**
	 * Uploads the contents of the file {@link #fileName} to the project's
	 * Google Code upload url. Performs the basic http authentication required
	 * by Google Code.
	 */
	private void upload() throws IOException {
		if (targetFileName == null) {

			if (classifier != null) {
				List attachedArtifacts = project.getAttachedArtifacts();
				for (Iterator iterator = attachedArtifacts.iterator(); iterator
						.hasNext();) {
					Artifact artifact = (Artifact) iterator.next();
					if (artifact.hasClassifier()) {
						if (classifier.equals(artifact.getClassifier())) {
							targetFileName = artifact.getFile()
									.getAbsolutePath();
						}
					}
				}
			}

		}
		if (targetFileName == null) {
			log("targetFileName is null, stopping... " );
		} else {
			System.clearProperty("javax.net.ssl.trustStoreProvider"); // fixes
																		// open-jdk-issue
			System.clearProperty("javax.net.ssl.trustStoreType");

			final String BOUNDARY = "CowMooCowMooCowCowCow";
			URL url = createUploadURL();

			log("The upload URL is " + url);

			InputStream in = new BufferedInputStream(new FileInputStream(
					fileName));

			HttpURLConnection conn = (HttpURLConnection) url.openConnection();

			if (this.isIgnoreSslCertificateHostname()) {
				if (conn instanceof HttpsURLConnection) {
					HttpsURLConnection secure = (HttpsURLConnection) conn;
					secure.setHostnameVerifier(new HostnameVerifier() {

						public boolean verify(String hostname,
								SSLSession session) {
							boolean result = true;
							log("SSL verification ignored for current session and hostname: "
									+ hostname);
							return result;
						}
					});
				}
			}
			Server server = settings.getServer("code.google.com");
			String userName = server.getUsername();
			String password = server.getPassword();

			conn.setDoOutput(true);
			conn.setRequestProperty("Authorization", "Basic "
					+ createAuthToken(userName, password));
			conn.setRequestProperty("Content-Type",
					"multipart/form-data; boundary=" + BOUNDARY);
			conn.setRequestProperty("User-Agent", "Google Code Upload Mojo 1.0");

			log("Attempting to connect (username is " + userName + ")...");
			conn.connect();

			log("Sending request parameters...");
			OutputStream out = conn.getOutputStream();
			sendLine(out, "--" + BOUNDARY);
			sendLine(out, "content-disposition: form-data; name=\"summary\"");
			sendLine(out, "");
			sendLine(out, summary);

			if (labels != null) {
				String[] labelArray = labels.split("\\,");

				if (labelArray != null && labelArray.length > 0) {
					log("Setting " + labelArray.length + " label(s)");

					for (int n = 0, i = labelArray.length; n < i; n++) {
						sendLine(out, "--" + BOUNDARY);
						sendLine(out,
								"content-disposition: form-data; name=\"label\"");
						sendLine(out, "");
						sendLine(out, labelArray[n].trim());
					}
				}
			}

			log("Sending file... " + targetFileName);
			sendLine(out, "--" + BOUNDARY);
			sendLine(out,
					"content-disposition: form-data; name=\"filename\"; filename=\""
							+ targetFileName + "\"");
			sendLine(out, "Content-Type: application/octet-stream");
			sendLine(out, "");
			int count;
			byte[] buf = new byte[8192];
			while ((count = in.read(buf)) >= 0) {
				out.write(buf, 0, count);
			}
			in.close();
			sendLine(out, "");
			sendLine(out, "--" + BOUNDARY + "--");

			out.flush();
			out.close();

			// For whatever reason, you have to read from the input stream
			// before
			// the url connection will start sending
			in = conn.getInputStream();

			log("Upload finished. Reading response.");

			log("HTTP Response Headers: " + conn.getHeaderFields());
			StringBuilder responseBody = new StringBuilder();
			while ((count = in.read(buf)) >= 0) {
				responseBody.append(new String(buf, 0, count, "ascii"));
			}
			log(responseBody.toString());
			in.close();

			conn.disconnect();
		}
	}

	private void log(String string) {
		getLog().debug(string);

	}

	private boolean isIgnoreSslCertificateHostname() {

		return ignoreSslCertificateHostname;
	}

	/**
	 * Just sends an ASCII version of the given string, followed by a CRLF line
	 * terminator, to the given output stream.
	 */
	private void sendLine(OutputStream out, String string) throws IOException {
		out.write(string.getBytes("ascii"));
		out.write("\r\n".getBytes("ascii"));
	}

	/**
	 * Creates a (base64-encoded) HTTP basic authentication token for the given
	 * user name and password.
	 */
	private static String createAuthToken(String userName, String password) {
		String string = (userName + ":" + password);
		try {

			return Base64.encodeBytes(string.getBytes("UTF-8"));
		} catch (java.io.UnsupportedEncodingException notreached) {
			throw new InternalError(notreached.toString());
		}
	}

	/**
	 * Creates the correct URL for uploading to the named google code project.
	 * If uploadUrl is not set (this is the standard case), the correct URL will
	 * be generated based on the {@link #projectName}. Otherwise, if uploadUrl
	 * is set, it will be used and the project name setting will be ignored.
	 */
	private URL createUploadURL() throws MalformedURLException {
		if (uploadUrl != null) {
			return new URL(uploadUrl);
		} else {
			if (projectName == null) {
				throw new NullPointerException("projectName must be set");
			}
			return new URL("https", projectName + ".googlecode.com", "/files");
		}
	}

}
