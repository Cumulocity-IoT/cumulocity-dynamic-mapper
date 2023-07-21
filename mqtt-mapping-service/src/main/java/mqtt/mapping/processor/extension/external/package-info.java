/**
 * This module holds all the externally loaded processor extenions.
 * External processor extensions have to:
 * 1. implement the inteface <code>ProcessorExtensionInbound<O></code> 
 * 2. be registered in the properties file <code>/mqtt-mapping-extension/src/main/resources/extension-external.properties</code>
 * 3. be developed/packed in the maven module <code>/mqtt-mapping-extension</code>. NOT in this maven module.
 * 4. be uploaded throught the Web UI
 * <p>

 * </p>
 *
 * @since 1.0
 * @author christof.strack
 * @version 1.1
 */

package mqtt.mapping.processor.extension.external;
