package mqtt.mapping.model;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InnerNodeSerializer extends StdSerializer<InnerNode> {
    
    public InnerNodeSerializer() {
        this(null);
    }
  
    public InnerNodeSerializer(Class<InnerNode> t) {
        super(t);
    }
    @Override
    public void serialize(
        InnerNode value, JsonGenerator jgen, SerializerProvider provider) 
      throws IOException, JsonProcessingException {
        log.debug("Serializing node {}, {}", value.getLevel(), value.getAbsolutePath() );
        jgen.writeStartObject();
        jgen.writeNumberField("depthIndex", value.getDepthIndex());
        jgen.writeStringField("level", value.getLevel());
        jgen.writeStringField("preTreeNode", (value.getPreTreeNode() != null ? value.getPreTreeNode().getAbsolutePath():"null"));
        jgen.writeStringField("absolutePath", value.getAbsolutePath());
        provider.defaultSerializeField("childNodes", value.getChildNodes(), jgen);
        jgen.writeEndObject();
    }
}