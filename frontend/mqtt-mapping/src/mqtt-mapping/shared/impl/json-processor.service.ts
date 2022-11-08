import * as _ from 'lodash';
import { PayloadProcessor } from "../payload-processor.service";
import { Mapping, API, RepairStrategy } from "../../../shared/mapping.model";
import { splitTopicExcludingSeparator, TOKEN_TOPIC_LEVEL, isNumeric, whatIsIt, TIME } from "../../../shared/util";
import { ProcessingContext, SubstituteValue, SubstituteValueType } from "../prosessor.model";
import { Injectable } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class JSONProcessor extends PayloadProcessor {

  public deserializePayload(context: ProcessingContext, mapping: Mapping) : ProcessingContext {
    context.payload = JSON.parse(mapping.source);
    return context;
  }

  public extractFromSource(context: ProcessingContext) {
    let mapping: Mapping = context.mapping;
    let payloadJsonNode: JSON = context.payload;
    let postProcessingCache: Map<string, SubstituteValue[]> = context.postProcessingCache;
    let topicLevels = splitTopicExcludingSeparator(context.topic);
    payloadJsonNode[TOKEN_TOPIC_LEVEL] = topicLevels;

    let payload: string = JSON.stringify(payloadJsonNode, null, 4);
    let substitutionTimeExists: boolean = false;

    mapping.substitutions.forEach(substitution => {
      let extractedSourceContent: JSON;
      // step 1 extract content from incoming payload
      extractedSourceContent = this.evaluateExpression(JSON.parse(mapping.source), substitution.pathSource, true);

      //step 2 analyse exctracted content: textual, array
      let postProcessingCacheEntry: SubstituteValue[] = _.get(postProcessingCache, substitution.pathTarget, []);
      if (extractedSourceContent == undefined) {
        console.error("No substitution for: ", substitution.pathSource, payload);
        postProcessingCacheEntry.push(
          {
            value: extractedSourceContent,
            type: SubstituteValueType.IGNORE,
            repairStrategy: substitution.repairStrategy
          });
        postProcessingCache.set(substitution.pathTarget, postProcessingCacheEntry);
      } else {
        if (Array.isArray(extractedSourceContent)) {
          if (substitution.expandArray) {
            // extracted result from sourcPayload is an array, so we potentially have to
            // iterate over the result, e.g. creating multiple devices
            extractedSourceContent.forEach(jn => {
              if (isNumeric(jn)) {
                postProcessingCacheEntry
                  .push({
                    value: jn.toString(),
                    type: SubstituteValueType.NUMBER,
                    repairStrategy: substitution.repairStrategy
                  });
              } else if (whatIsIt(jn) == 'String') {
                postProcessingCacheEntry
                  .push({
                    value: jn,
                    type: SubstituteValueType.TEXTUAL,
                    repairStrategy: substitution.repairStrategy
                  });
              } else if (whatIsIt(jn) == 'Array') {
                postProcessingCacheEntry
                  .push({
                    value: jn,
                    type: SubstituteValueType.ARRAY,
                    repairStrategy: substitution.repairStrategy
                  });
              } else {
                console.warn(`Since result is not textual or number it is ignored: ${jn}`);
              }
            })
            context.cardinality.set(substitution.pathTarget, extractedSourceContent.length);
            postProcessingCache.set(substitution.pathTarget, postProcessingCacheEntry);
          } else {
            // treat this extracted enry as single value, no MULTI_VALUE or MULTI_DEVICE substitution
            context.cardinality.set(substitution.pathTarget, 1);
            postProcessingCacheEntry
              .push({
                value: extractedSourceContent,
                type: SubstituteValueType.ARRAY,
                repairStrategy: substitution.repairStrategy
              })
            postProcessingCache.set(substitution.pathTarget, postProcessingCacheEntry);
          }
        } else if (isNumeric(JSON.stringify(extractedSourceContent))) {
          context.cardinality.set(substitution.pathTarget, 1);
          postProcessingCacheEntry.push(
            { value: extractedSourceContent, type: SubstituteValueType.NUMBER, repairStrategy: substitution.repairStrategy });
          postProcessingCache.set(substitution.pathTarget, postProcessingCacheEntry);
        } else if (whatIsIt(extractedSourceContent) == "String") {
          context.cardinality.set(substitution.pathTarget, 1);
          postProcessingCacheEntry.push(
            { value: extractedSourceContent, type: SubstituteValueType.TEXTUAL, repairStrategy: substitution.repairStrategy });
          postProcessingCache.set(substitution.pathTarget, postProcessingCacheEntry);
        } else {
          console.log(`This substitution, involves an objects for: ${substitution.pathSource}, ${extractedSourceContent}`);
          context.cardinality.set(substitution.pathTarget, 1);
          postProcessingCacheEntry.push(
            { value: extractedSourceContent, type: SubstituteValueType.OBJECT, repairStrategy: substitution.repairStrategy });
          postProcessingCache.set(substitution.pathTarget, postProcessingCacheEntry);
        }
        console.log(`Evaluated substitution (pathSource:substitute)/(${substitution.pathSource}:${extractedSourceContent}), (pathTarget)/(${substitution.pathTarget}`);
      }
      if (substitution.pathTarget === TIME) {
        substitutionTimeExists = true;
      }
    })

    // no substitution for the time property exists, then use the system time
    if (!substitutionTimeExists && mapping.targetAPI != API.INVENTORY.name) {
      let postProcessingCacheEntry: SubstituteValue[] = _.get(postProcessingCache, TIME, []);
      postProcessingCacheEntry.push(
        { value: new Date().toISOString(), type: SubstituteValueType.TEXTUAL, repairStrategy: RepairStrategy.DEFAULT });

      postProcessingCache.set(TIME, postProcessingCacheEntry);
    }
  }

  public evaluateExpression(json: JSON, path: string, flat: boolean): JSON {
    let result: any = '';
    if (path != undefined && path != '' && json != undefined) {
      const expression = this.JSONATA(path)
      result = expression.evaluate(json) as JSON
    }
    return result;
  }

}