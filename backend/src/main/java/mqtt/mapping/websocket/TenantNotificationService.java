package mqtt.mapping.websocket;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionFilterRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationTokenRequestRepresentation;
import com.cumulocity.sdk.client.messaging.notifications.NotificationSubscriptionApi;
import com.cumulocity.sdk.client.messaging.notifications.NotificationSubscriptionCollection;
import com.cumulocity.sdk.client.messaging.notifications.NotificationSubscriptionFilter;
import com.cumulocity.sdk.client.messaging.notifications.TokenApi;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.model.API;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.websocket.jetty.JettyWebSocketClient;
import mqtt.mapping.websocket.tootallnate.TooTallNateWebSocketClient;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

@Service
@Slf4j
public class TenantNotificationService {

    private final static String WEBSOCKET_URL_PATTERN = "%s/notification2/consumer/?token=%s";

    @Autowired
    private TokenApi tokenApi;

    @Autowired
    private NotificationSubscriptionApi notificationSubscriptionApi;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    @Autowired
    private Properties properties;

    private final Map<String, ActiveSocketClientVo> socketClientMapping = new HashMap<String, ActiveSocketClientVo>();

    public String registerNotification(final String tenant,Mapping mapping,NotificationCallback _notificationCallback) throws Exception {
        Exception[] es = new Exception[]{null};
        subscriptionsService.runForTenant(tenant,()-> {
            // Create Subscription for source device
            final NotificationSubscriptionRepresentation subscriptionRepresentation = createSubscription(mapping);

            if (subscriptionRepresentation != null) {
                // Obtain authorization token
                final String token = createToken(subscriptionRepresentation.getSubscription());
                // Connect to WebSocket server to receive notifications
                ActiveSocketClientVo _vo = new ActiveSocketClientVo(tenant,subscriptionRepresentation.getId().getValue(),
                        token);
                try {
                    connectAndReceiveNotifications(mapping, _vo, _notificationCallback);
                }catch(Exception e){
                    es[0] = e;
                }
            }
        });
        if(es[0]!=null){
            throw es[0];
        }
        return genSubscriptionKey(mapping);
    }

    public void closeUnActiveMapping(Collection<String> activeSocketKeys){
        Set<String> unActiveKeys = new HashSet<>();
        socketClientMapping.keySet().forEach(_cacheKey -> {
            if(!activeSocketKeys.contains(_cacheKey)){
                log.info("need disable websocket client with key:{}",_cacheKey);
                ActiveSocketClientVo clientVo = socketClientMapping.get(_cacheKey);
                clientVo.getSocketClient().close(1000,"unactive");
                unActiveKeys.add(_cacheKey);
            }
        });
        //clean cache
        for (String unActiveKey : unActiveKeys) {
            socketClientMapping.remove(unActiveKey);
        }
    }

    public void closeAllNotUserNotification(String tenant){
        subscriptionsService.runForTenant(tenant, () -> {
            socketClientMapping.values().forEach(vo->{
                vo.getSocketClient().close(1000,"renew");
            });
        });
        socketClientMapping.clear();
    }

    private NotificationSubscriptionRepresentation createSubscription(Mapping mapping) {

        final NotificationSubscriptionFilterRepresentation filterRepresentation = new NotificationSubscriptionFilterRepresentation();

        if(API.ALARM.equals(mapping.getTargetAPI())) {
            filterRepresentation.setApis(List.of("alarms"));
        }else{
            return null;
        }
        if(StringUtils.isNotBlank(mapping.getFilterType())) {
            filterRepresentation.setTypeFilter(mapping.getFilterType());
        }

        NotificationSubscriptionRepresentation subscriptionRepresentation = getSampleSubscriptionRepresentation(filterRepresentation);
        if(subscriptionRepresentation.getId() == null){
            log.info("Creating subscription...");
            NotificationSubscriptionRepresentation newSubscription = notificationSubscriptionApi.subscribe(subscriptionRepresentation);
            return newSubscription;
        }else{
            return subscriptionRepresentation;
        }
    }

    protected String genSubscriptionKey(Mapping _mapping){
        return String.format("%s_%s", StringUtils.join(_mapping.getTarget()),
                _mapping.getFilterType());
    }

    private void connectAndReceiveNotifications(Mapping _mapping, final ActiveSocketClientVo _vo,NotificationCallback _notificationCallback) throws Exception {

        final URI webSocketUri = getWebSocketUrl(_vo.getSocketToken());

        final String mappingCacheKey = genSubscriptionKey(_mapping);
        final NotificationCallback callback = new NotificationCallback() {


            ActiveSocketClientVo _thisVo = _vo;

            @Override
            public void onOpen(URI uri) {
                log.info("Connected to Cumulocity notification service over WebSocket " + uri);
                socketClientMapping.put(mappingCacheKey, _vo);
            }

            @Override
            public void onNotification(Notification notification) {
                log.info("Notification received: <{}>", notification.getMessage());
                _notificationCallback.onNotification(notification);
            }

            @Override
            public void onError(Throwable t) {
                log.error("We got an exception: " + t);
                _notificationCallback.onError(t);
            }

            @Override
            public void onClose() {
                log.info("Connection was closed.");
                _notificationCallback.onClose();
                //delete subscription
                if(_thisVo.getSubscriptionId()!=null){
                    //log.info("delete subscription {}", _thisVo.getSubscriptionId());
                    subscriptionsService.runForTenant(_thisVo.getTenant(), () -> {
                        //deleteSubscription(_thisVo.getSubscriptionId());
                    });
                }
            }
        };

        final String webSocketLibrary = properties.getWebSocketLibrary();
        log.info("webSocketUri:{}",webSocketUri);
        WebSocketClientInterface clientInterface = null;
        if (webSocketLibrary != null && webSocketLibrary.equalsIgnoreCase("jetty")) {
            log.info("WebSocket library: Jetty");
            final JettyWebSocketClient client = new JettyWebSocketClient(webSocketUri, callback);
            client.connect();
            clientInterface = client;
        } else {
            log.info("WebSocket library: TooTallNate");
            final TooTallNateWebSocketClient client = new TooTallNateWebSocketClient(webSocketUri, callback);
            client.connect();
            clientInterface = client;
        }
        _vo.setSocketClient(clientInterface);
    }

    private NotificationSubscriptionRepresentation getSampleSubscriptionRepresentation(NotificationSubscriptionFilterRepresentation filterRepresentation) {

        String subscriptionName =
                "genericMQTTNotification"+filterRepresentation.getTypeFilter()+ StringUtils.join(filterRepresentation.getApis(),"");
        subscriptionName = subscriptionName.replaceAll("_","");
        //subscription need reuse
        final String _finalSubscriptionName = subscriptionName;
        final NotificationSubscriptionCollection notificationSubscriptionCollection = notificationSubscriptionApi
                .getSubscriptionsByFilter(new NotificationSubscriptionFilter().byContext("tenant"));
        final List<NotificationSubscriptionRepresentation> subscriptions = notificationSubscriptionCollection.get().getSubscriptions();
        final Optional<NotificationSubscriptionRepresentation> subscriptionRepresentationOptional = subscriptions.stream()
                .filter(subscription -> subscription.getSubscription().equals(_finalSubscriptionName))
                .findFirst();

        if (subscriptionRepresentationOptional.isPresent()) {
            NotificationSubscriptionRepresentation nsr = subscriptionRepresentationOptional.get();
            log.info("Reusing existing subscription <{}>-<{}> ontenant", subscriptionName, nsr.getId().getValue());
            return nsr;
        }

        final NotificationSubscriptionRepresentation subscriptionRepresentation = new NotificationSubscriptionRepresentation();
        subscriptionRepresentation.setContext("tenant");
        subscriptionRepresentation.setSubscription(subscriptionName);
        subscriptionRepresentation.setSubscriptionFilter(filterRepresentation);

        return subscriptionRepresentation;
    }

    private String createToken(String subscription) {
        log.info("create token for subscription:{}", subscription);
        final NotificationTokenRequestRepresentation tokenRequestRepresentation = new NotificationTokenRequestRepresentation(
                properties.getSubscriber(),
                subscription,
                1440,
                false);
        return tokenApi.create(tokenRequestRepresentation).getTokenString();
    }

    private URI getWebSocketUrl(String token) throws URISyntaxException {
        return new URI(String.format(WEBSOCKET_URL_PATTERN, properties.getWebSocketBaseUrl(), token));
    }

//    private void deleteSubscription(String subscriptionId){
//        NotificationSubscriptionRepresentation subscriptionRepresentation = new NotificationSubscriptionRepresentation();
//        subscriptionRepresentation.setId(GId.asGId(subscriptionId));
//        notificationSubscriptionApi.delete(subscriptionRepresentation);
//    }
}
