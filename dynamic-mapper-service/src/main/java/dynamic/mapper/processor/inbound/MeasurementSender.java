package dynamic.mapper.processor.inbound;



import dynamic.mapper.model.API;
import dynamic.mapper.processor.model.C8YRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MeasurementSender extends BaseMessageSender<Object> {

    @Override
    protected C8YRequest createRequest(ProcessingContext<Object> context) {
        try {
            return context.getCurrentRequest();

        } catch (Exception e) {
            log.error("Failed to create measurement request: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create measurement request", e);
        }
    }

    @Override
    protected API getAPI() {
        return API.MEASUREMENT;
    }

}