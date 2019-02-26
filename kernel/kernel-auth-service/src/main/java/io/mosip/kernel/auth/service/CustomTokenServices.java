/**
 * 
 */
package io.mosip.kernel.auth.service;

import io.mosip.kernel.auth.entities.AuthToken;
import io.mosip.kernel.auth.entities.TimeToken;

/**
 * @author Ramadurai Pandian
 *
 */
public interface CustomTokenServices {
	
	void StoreToken(AuthToken token);
	
	void refreshtoken(String token);
	
	void invalidateToken(String token);
	
	AuthToken getTokenDetails(String token);

	AuthToken getUpdatedAccessToken(String token, TimeToken newAccessToken, String userName);

	void revokeToken(String token);	

}
