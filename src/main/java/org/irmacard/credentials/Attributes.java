/*
 * Copyright (c) 2015, the IRMA Team
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 *  Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 *  Neither the name of the IRMA project nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.irmacard.credentials;

import org.irmacard.credentials.info.*;

import java.io.Serializable;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * A generic container class for attributes. Indexes attributes (in the form
 * of byte arrays) by name, and provides getters and setters for all metadata
 * fields.
 */
@SuppressWarnings("unused")
public class Attributes implements Serializable {
	private static final long serialVersionUID = 1L;

	private Map<String, byte[]> attributes;

	/**
	 * Precision factor for the expiry attribute, 1 means millisecond precision.
	 * Set to one week.
	 */
	public final static long EXPIRY_FACTOR = 1000 * 60 * 60 * 24 * 7;
	public final static String META_DATA_FIELD = "metadata";
	public final static int META_DATA_LENGTH; // Computed below
	public final static byte CURRENT_VERSION = (byte) 0x02;
	public final static int FIELD_COUNT = Field.values().length;

	/** The metadata fields. The order in which they appear here defines the order in which
	 *  they are stored in the metadata attribute. */
	private enum Field {
		VERSION(1),
		SIGNING_DATE(3),
		EXPIRY(2),
		KEY_COUNTER(2),
		CREDENTIAL_ID(16);

		private int length;
		private int offset;

		Field(int length) {
			this.length = length;
		}
	}

	static {
		Field[] fields = Field.values();
		int offset = 0;

		fields[0].offset = 0;
		for (int i = 1; i < FIELD_COUNT; ++i) {
			offset += fields[i-1].length;
			fields[i].offset = offset;
		}

		Field lastField = fields[FIELD_COUNT-1];
		META_DATA_LENGTH = lastField.offset + lastField.length;
	}

	/**
	 * Create a new instance with all metadata fields set to their default values.
	 */
	public Attributes() {
		attributes = new HashMap<>();

		// Create meta-data field
		byte[] metadata = new byte[META_DATA_LENGTH];
		add(META_DATA_FIELD, metadata);

		// Set metadata fields to their default values
		setVersion(CURRENT_VERSION);
		setSigningDate(null);
		setValidityDuration((short)(52 / 2));
		setKeyCounter(0);
	}

	/**
	 * Create a new instance with the specified attributes. The credential type is extracted from the
	 * metadata attribute (no. 1), which must be present. The corresponding attribute names are fetched
	 * from the {@link DescriptionStore}. If an attribute is not present in the hash map, its value
	 * becomes 0.
	 * @param values The attribute values
	 * @throws IllegalArgumentException If the metadata attribute was absent, i.e., values.get(1) == null,
	 *                                  or if the credential type is unknown (i.e. not present in the
	 *                                  DescriptionStore)
	 */
	public Attributes(HashMap<Integer, BigInteger> values) throws IllegalArgumentException {
		if (values.get(1) == null)
			throw new IllegalArgumentException("Metadata attribute was absent but is compulsory");

		attributes = new HashMap<>();
		add(META_DATA_FIELD, values.get(1));

		CredentialDescription cd = getCredentialDescription();
		if (cd == null)
			throw new IllegalArgumentException("Credential type not found in store");

		List<String> attributeNames = cd.getAttributeNames();
		for (int i = 0; i < attributeNames.size(); ++i) {
			BigInteger attribute = values.get(i+2);
			if (attribute != null)
				add(attributeNames.get(i), attribute);
		}
	}

	/**
	 * Create a new instance with the specified attributes. The credential type is extracted from the
	 * metadata attribute (no. 1). The corresponding attribute names are fetched from the
	 * {@link DescriptionStore}.
	 * @param values The attribute values, with the metadata attribute at position 1 and the rest of
	 *               the attributes at position 2 and on.
	 * @throws IllegalArgumentException If the metadata attribute was absent, i.e., values.get(1) == null,
	 *                                  or if the credential type is unknown (i.e. not present in the
	 *                                  DescriptionStore)
	 */
	public Attributes(List<BigInteger> values) {
		if (values.get(1) == null)
			throw new IllegalArgumentException("Metadata attribute was absent but is compulsory");

		attributes = new HashMap<>();
		add(META_DATA_FIELD, values.get(1));

		CredentialDescription cd = getCredentialDescription();
		if (cd == null)
			throw new IllegalArgumentException("Credential type not found in store");

		List<String> attributeNames = cd.getAttributeNames();
		for (int i = 0; i < attributeNames.size(); ++i)
			add(attributeNames.get(i), values.get(i+2));
	}

	/**
	 * Create a new instance containing the specified BigInteger as metadat attribute.
	 */
	public Attributes(BigInteger metadata) {
		attributes = new HashMap<>();
		attributes.put(META_DATA_FIELD, metadata.toByteArray());
	}

	private byte[] getMetadataField(Field field) {
		return Arrays.copyOfRange(attributes.get(META_DATA_FIELD), field.offset, field.offset + field.length);
	}

	private void setMetadataField(byte[] value, Field field) {
		byte[] metadata = attributes.get(META_DATA_FIELD);

		if (value.length > field.length)
			throw new RuntimeException("Metadata field value for " + field
					+ " too long: was " + value.length + ", maximum is " + field.length);

		// Populate the bytes, pushing them as far right as possible within the space allotted to this field
		int emptySpace = field.length - value.length;
		ByteBuffer buffer = ByteBuffer.wrap(metadata);
		buffer.position(field.offset);
		if (emptySpace > 0)
			buffer.put(new byte[emptySpace]);
		buffer.put(value);
		add(META_DATA_FIELD, buffer.array());
	}

	public void add(String id, byte[] value) {
		attributes.put(id, value);
	}

	public void add(String id, BigInteger value) {
		if (!id.equals(META_DATA_FIELD) && getVersion() >= 3) {
			if (value.testBit(0)) {
				// This attribute has a value (possibly zero-length).
				// Drop the least-significant bit and convert to a byte[].
				byte[] bytes = value.shiftRight(1).toByteArray();
				if (bytes.length == 1 && bytes[0] == 0) {
					// Empty but set attribute. Work around BigInteger limitation.
					attributes.put(id, new byte[]{});
				} else {
					attributes.put(id, bytes);
				}
			} else {
				// This attribute is not set.
				attributes.put(id, null);
			}
		} else {
			// No shifting applied
			attributes.put(id, value.toByteArray());
		}
	}

	public byte[] get(String id) {
		return attributes.get(id);
	}

	public Set<String> getIdentifiers() {
		return attributes.keySet();
	}

	public void print() {
		for(String k : attributes.keySet() ) {
			System.out.println(toString());
		}
	}

	public String toString() {
		String res = "[";
		for(String k : attributes.keySet() ) {
			res += k + ": " + new String(get(k)) + ", ";
		}
		res += "]";
		return res;
	}

	/**
	 * Get the version number for this credential.
	 * Version >= 3 supports optional attributes.
	 */
	public byte getVersion() {
		return getMetadataField(Field.VERSION)[0];
	}

	/**
	 * Set the version number for this credential.
	 * Version >= 3 supports optional attributes.
	 */
	public void setVersion(byte version) {
		setMetadataField(new byte[]{version}, Field.VERSION);
	}

	/**
	 * Get the validity duration in weeks for this credential.
	 */
	public short getValidityDuration() {
		return ByteBuffer.wrap(getMetadataField(Field.EXPIRY)).getShort();
	}

	/**
	 * Set the validity duration in weeks for this credential.
	 */
	public void setValidityDuration(short numberOfWeeks) {
		setMetadataField(ByteBuffer.allocate(2).putShort(numberOfWeeks).array(), Field.EXPIRY);
	}

	/**
	 * Get the expiry date for this credential.
	 */
	public Date getExpiryDate() {
		Date signing = getSigningDate();
		long duration = getValidityDuration() * EXPIRY_FACTOR;

		return new Date(signing.getTime() + duration);
	}

	/**
	 * Set the expiry date for this credential.
	 * @param expiry The desired expiry date
	 * @throws IllegalArgumentException if the specified date does not fall on an epoch boundary
	 */
	public void setExpiryDate(Date expiry) throws IllegalArgumentException {
		long expiryLong = expiry.getTime();
		if (expiryLong % EXPIRY_FACTOR != 0)
			throw new IllegalArgumentException("Expiry date does not match an epoch boundary");

		long signingLong = getSigningDate().getTime();
		short difference = (short)((expiryLong - signingLong) / EXPIRY_FACTOR);

		setMetadataField(ByteBuffer.allocate(2).putShort(difference).array(), Field.EXPIRY);
	}

	/**
	 * Get the date on which this credential was signed.
	 */
	public Date getSigningDate() {
		byte[] signingDateBytes = getMetadataField(Field.SIGNING_DATE);
		long signingDateLong = new BigInteger(signingDateBytes).longValue();

		Calendar signingDate = Calendar.getInstance();
		signingDate.setTimeInMillis(signingDateLong * EXPIRY_FACTOR);
		return signingDate.getTime();
	}

	/**
	 * Set the date on which this credential was signed.
	 */
	public void setSigningDate(Date date) {
		long value;
		if (date != null)
			value = date.getTime() / EXPIRY_FACTOR;
		else
			value = Calendar.getInstance().getTimeInMillis() / EXPIRY_FACTOR;

		setMetadataField(BigInteger.valueOf(value).toByteArray(), Field.SIGNING_DATE);
	}

	/**
	 * Get the counter of the public key with which this credential was signed.
	 */
	public short getKeyCounter() {
		return ByteBuffer.wrap(getMetadataField(Field.KEY_COUNTER)).getShort();
	}

	/**
	 * Set the counter of the public key with which this credential was signed.
	 */
	public void setKeyCounter(int value) {
		setMetadataField(ByteBuffer.allocate(2).putShort((short)value).array(), Field.KEY_COUNTER);
	}

	/**
	 * Get the {@link CredentialIdentifier} from the metadata attribute of this instance.
	 * @return Either the identifier, or null if the {@link DescriptionStore} does not contain it.
	 * @throws StoreException if the {@link DescriptionStore} is not initialized
	 */
	public CredentialIdentifier getCredentialIdentifier() throws StoreException {
		return DescriptionStore.getInstance().hashToCredentialIdentifier(getMetadataField(Field.CREDENTIAL_ID));
	}

	/**
	 * Set the {@link CredentialIdentifier} of this credential.
	 */
	public void setCredentialIdentifier(CredentialIdentifier value) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			md.update(value.toString().getBytes());
			byte[] truncatedHash = Arrays.copyOfRange(md.digest(), 0, 16);
			setMetadataField(truncatedHash, Field.CREDENTIAL_ID);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("SHA-256 hash algorithm absent");
		}
	}

	/**
	 * Get the {@link CredentialDescription} of this credential, using {@link #getCredentialIdentifier()}
	 * and the {@link DescriptionStore}.
	 * @throws StoreException if the {@link DescriptionStore} is not initialized
	 */
	public CredentialDescription getCredentialDescription() {
		return DescriptionStore.getInstance().getCredentialDescription(getCredentialIdentifier());
	}

	/**
	 * Test whether the the credential containing these attributes is still valid on the given date.
	 * @return True if the specified date is before the expiry dates of both the credential and the
	 *         corresponding public key; false otherwise.
	 * @throws InfoException when the {@link CredentialIdentifier} could not be determined using the metadata attribute;
	 *                       when the {@link KeyStore} has not been initialized; or when it does not contain the
	 *                       required public key.
	 * @throws KeyException when the corresponding public key could not be found.
	 */
	public boolean isValidOn(Date date) throws InfoException, KeyException {
		try {
			IssuerIdentifier issuer = getCredentialIdentifier().getIssuerIdentifier();
			PublicKey key = KeyStore.getInstance().getPublicKey(issuer, getKeyCounter());
			return key.isValidOn(getSigningDate()) && !isExpiredOn(date);
		} catch (NullPointerException|StoreException e) {
			throw new InfoException(e);
		}
	}

	/**
	 * @return {@link #isValidOn(Date)} the current time.
	 */
	public boolean isValid() throws InfoException, KeyException {
		return isValidOn(Calendar.getInstance().getTime());
	}

	/** Returns true iff the credential is expired on the given date. */
	public boolean isExpiredOn(Date date) {
		return date.after(getExpiryDate());
	}

	/** Returns true iff the credential is expired at the current time. */
	public boolean isExpired() {
		return isExpiredOn(Calendar.getInstance().getTime());
	}

	/**
	 * Convert this instance to a list of BigIntegers, suitable for passing to the Idemix API.
	 * The secret key is not included so the first element is the metadata attribute.
	 * @throws InfoException if there is a mismatch between the attribute names according to this
	 *                       instance and the {@link DescriptionStore}
	 */
	public ArrayList<BigInteger> toBigIntegers() throws InfoException {
		List<AttributeDescription> ads = getCredentialDescription().getAttributes();
		ArrayList<BigInteger> bigints = new ArrayList<>(ads.size() + 2);

		// Add metadata field manually as it is not included in .getAttributeNames()
		bigints.add(new BigInteger(get(META_DATA_FIELD)));

		// Add the other attributes
		for (AttributeDescription ad : ads) {
			byte[] value = get(ad.getName());

			// Check for empty attributes that are non-optional
			if (value == null || value.length == 0) {
				if (!ad.isOptional()) {
					throw new InfoException("Attribute " + ad.getName() + " missing");
				}
			}

			if (value == null) {
				// This attribute does not exist.
				bigints.add(new BigInteger(new byte[]{0}));
			} else if (value.length == 0) {
				// This attribute exists, but is a zero-length string.
				// (Workaround for Java BigInteger requiring bytearrays with
				// length > 0)
				if (getVersion() < 3) {
					throw new InfoException("Cannot encode zero-length attribute " + ad.getName() + " at version < 3");
				}
				bigints.add(new BigInteger(new byte[]{1}));
			} else {
				// This attribute exists, and it has a non-zero value.
				// We want to be compatible with the Go implementation, in which the string -> bigint conversion
				// always yields positive bigints. By contrast, in the Java string -> bigint conversion, the most
				// significant bit is interpreted as a sign bit (two's complement). In order to prevent this we
				// prepend an extra 0-byte in the byte[] -> bigint conversion (the String -> byte[] conversion
				// has already happened elsewhere), ensuring that we always get a positive bigint.
				byte[] positiveBytes = new byte[value.length+1];
				positiveBytes[0] = 0;
				System.arraycopy(value, 0, positiveBytes, 1, value.length);
				BigInteger big = new BigInteger(positiveBytes);
				if (getVersion() >= 3) {
					big = big.shiftLeft(1).setBit(0);
				}
				bigints.add(big);
			}
		}

		return bigints;
	}
}
