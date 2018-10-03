package org.irmacard.api.common;

import org.irmacard.api.common.signatures.SignatureProofRequest;
import org.irmacard.api.common.signatures.SignatureProofResult;
import org.irmacard.api.common.timestamp.Timestamp;
import org.irmacard.credentials.idemix.proofs.ProofList;
import org.irmacard.credentials.info.AttributeIdentifier;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.credentials.info.KeyException;

import java.math.BigInteger;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;

public class IrmaSignedMessage {
	private final int v = 1;
	private ProofList signature;
	private BigInteger nonce;
	private BigInteger context;
	private String message;
	private String messageType;
	private Timestamp timestamp;

	private transient Map<AttributeIdentifier, String> attributes;

	public IrmaSignedMessage(ProofList proofs, BigInteger nonce, BigInteger context, String message, String messageType, Timestamp timestamp) {
		this.signature = proofs;
		this.nonce = nonce;
		this.context = context;
		this.message = message;
		this.messageType = messageType;
		this.timestamp = timestamp;
	}

	public Map<AttributeIdentifier, String> getAttributes() throws IllegalArgumentException {
		if (attributes == null)
			attributes = signature.getAttributes();

		return attributes;
	}

	public SignatureProofResult verify() throws InfoException, KeyException {
		return verify(Calendar.getInstance().getTime(), true);
	}

	public SignatureProofResult verify(Date validityDate) throws InfoException, KeyException {
		return verify(validityDate, false);
	}

	public SignatureProofResult verify(Date validityDate, boolean allowExpired)
	throws InfoException, KeyException {
		return verify(null, validityDate, allowExpired);
	}

	public SignatureProofResult verify(SignatureProofRequest request, Date validityDate, boolean allowExpired)
			throws InfoException, KeyException {
		signature.populatePublicKeyArray();
		signature.setSig(true); // Verify this as an ABS (as opposed to a disclosure proof list)

		// Verify with 'empty' request if request was not set
		if (request == null) {
			request = new SignatureProofRequest(nonce, context,
					new AttributeDisjunctionList(), message, messageType);
			request.setTimestamp(getTimestamp());
		}

		SignatureProofResult result = request.verify(signature, validityDate, allowExpired);

		attributes = result.getAttributes();
		return result;
	}

	public String getMessage() {
		return message;
	}

	public String getMessageType() {
		return messageType;
	}

	public ProofList getProofs() {
		return signature;
	}

	public BigInteger getNonce() {
		return nonce;
	}

	public BigInteger getContext() {
		return context;
	}

	public Timestamp getTimestamp() {
		return timestamp;
	}

	public int getVersion() {
		return v;
	}
}
