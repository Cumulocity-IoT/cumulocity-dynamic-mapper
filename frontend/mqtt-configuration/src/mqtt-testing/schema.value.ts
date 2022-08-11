export const schema = {
    'definitions': {},
    '$schema': 'http://json-schema.org/draft-07/schema#',
    '$id': 'http://example.com/root.json',
    'type': 'object',
    'title': 'The Root Schema',
    'required': [
      'randomNumber',
      'products'
    ],
    'properties': {
      'randomNumber': {
        '$id': '#/properties/randomNumber',
        'type': 'integer',
        'title': 'The Randomnumber Schema',
        'default': 0,
        'examples': [
          10
        ],
        'enum': [1, 2, 3, 4, 5, 6, 7, 8]
      },
      'products': {
        '$id': '#/properties/products',
        'type': 'array',
        'title': 'The Products Schema',
        'items': {
          '$id': '#/properties/products/items',
          'type': 'object',
          'title': 'The Items Schema',
          'required': [
            'name',
            'product'
          ],
          'properties': {
            'name': {
              '$id': '#/properties/products/items/properties/name',
              'type': 'string',
              'title': 'The Name Schema',
              'default': '',
              'examples': [
                'car'
              ],
              'pattern': '^(.*)$'
            },
            'product': {
              '$id': '#/properties/products/items/properties/product',
              'type': 'array',
              'title': 'The Product Schema',
              'items': {
                '$id': '#/properties/products/items/properties/product/items',
                'type': 'object',
                'title': 'The Items Schema',
                'required': [
                  'name',
                  'model'
                ],
                'properties': {
                  'name': {
                    '$id': '#/properties/products/items/properties/product/items/properties/name',
                    'type': 'string',
                    'title': 'The Name Schema',
                    'default': '',
                    'examples': [
                      'honda'
                    ],
                    'pattern': '^(.*)$'
                  },
                  'model': {
                    '$id': '#/properties/products/items/properties/product/items/properties/model',
                    'type': 'array',
                    'title': 'The Model Schema',
                    'items': {
                      '$id': '#/properties/products/items/properties/product/items/properties/model/items',
                      'type': 'object',
                      'title': 'The Items Schema',
                      'required': [
                        'id',
                        'name'
                      ],
                      'properties': {
                        'id': {
                          '$id': '#/properties/products/items/properties/product/items/properties/model/items/properties/id',
                          'type': 'string',
                          'title': 'The Id Schema',
                          'default': '',
                          'examples': [
                            'civic'
                          ],
                          'pattern': '^(.*)$'
                        },
                        'name': {
                          '$id': '#/properties/products/items/properties/product/items/properties/model/items/properties/name',
                          'type': 'string',
                          'title': 'The Name Schema',
                          'default': '',
                          'examples': [
                            'civic'
                          ],
                          'pattern': '^(.*)$'
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

export const schema_event = {
  'definitions': {},
  '$schema': 'http://json-schema.org/draft-07/schema#',
  '$id': 'http://example.com/root.json',
  'type': 'object',
  'title': 'Event',
  'required': [
      'source',
      'type',
      'text',
      'time'
    ],
    'properties': {
      'source': {
        '$id': '#/properties/source',
        'type': 'object',
        'title': 'The managed object to which the event is associated.',
        'properties': {
          'id': {
            'type': 'string',
            'minLength': 1,
            'title': 'SourceID'
          }
        }
      },
      'type':{
        '$id': '#/properties/type',
        'type': 'string',
        'title': 'Type of the event.',
      },
      'text':{
        '$id': '#/properties/text',
        'type': 'string',
        'title': 'Text of the event.',
      },
      'time':{
        '$id': '#/properties/time',
        'type': 'string',
        'title': 'Type of the event.',
        'pattern': '^((?:(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?))(Z|[\+-]\\d{2}:\\d{2})?)$'
      }
    }
}

export const schema_alarm = {
  'definitions': {},
  '$schema': 'http://json-schema.org/draft-07/schema#',
  '$id': 'http://example.com/root.json',
  'type': 'object',
  'title': 'Alarm',
  'required': [
      'source',
      'type',
      'text',
      'time',
      'severity'
    ],
    'properties': {
      'source': {
        '$id': '#/properties/source',
        'type': 'object',
        'title': 'The managed object to which the alarm is associated.',
        'properties': {
          'id': {
            'type': 'string',
            'minLength': 1,
            'title': 'SourceID'
          }
        }
      },
      'type':{
        '$id': '#/properties/type',
        'type': 'string',
        'title': 'Type of the alarm.',
      },

      'severity':{
        '$id': '#/properties/severity',
        'type': 'string',
        'title': 'Severity of the alarm.',
        'pattern': '^((CRITICAL)|(MAJOR)|(MINOR)|(WARNING))$'
      },
      'text':{
        '$id': '#/properties/text',
        'type': 'string',
        'title': 'Text of the alarm.',
      },
      'time':{
        '$id': '#/properties/time',
        'type': 'string',
        'title': 'Type of the alarm.',
        'pattern': '^((?:(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?))(Z|[\+-]\\d{2}:\\d{2})?)$'
      }
    }
}
  
export const schema_measurement = {
  'definitions': {},
  '$schema': 'http://json-schema.org/draft-07/schema#',
  '$id': 'http://example.com/root.json',
  'type': 'object',
  'title': 'Measurement',
  'required': [
      'source',
      'type',
      'time',
    ],
    'properties': {
      'source': {
        '$id': '#/properties/source',
        'type': 'object',
        'title': 'The managed object to which the measurement is associated.',
        'properties': {
          'id': {
            'type': 'string',
            'minLength': 1,
            'title': 'SourceID'
          }
        }
      },
      'type':{
        '$id': '#/properties/type',
        'type': 'string',
        'title': 'Type of the measurement.',
      },
      'time':{
        '$id': '#/properties/time',
        'type': 'string',
        'title': 'Type of the measurement.',
        'pattern': '^((?:(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?))(Z|[\+-]\\d{2}:\\d{2})?)$'
      }
    }
}