import { Component, OnInit, ViewChild, ViewEncapsulation } from '@angular/core';
import { JsonEditorComponent, JsonEditorOptions } from '@maaxgr/ang-jsoneditor';
import { FormBuilder } from '@angular/forms';
import { schema, schema_alarm, schema_event } from './schema.value';

@Component({
  selector: 'mqtt-testing',
  templateUrl: './mqtt-testing.component.html',
  styleUrls: ['./mqtt-testing.component.scss'],
  encapsulation: ViewEncapsulation.None
})
export class MqttTestingComponent implements OnInit {

  public editorOptions: JsonEditorOptions;
  public data: any;

  public editorOptions2: JsonEditorOptions;
  public data2: any;

  public showData: any;

  public show = false;

  @ViewChild('editor', { static: false }) editor!: JsonEditorComponent;

  public form: any;
  public formData: any;

  dataMulti: any = {
    products: [{
      name: 'car',
      product: [{
        name: 'honda',
        model: [
          { id: 'civic', name: 'civic' },
          { id: 'accord', name: 'accord' },
          { id: 'crv', name: 'crv' },
          { id: 'pilot', name: 'pilot' },
          { id: 'odyssey', name: 'odyssey' }
        ]
      }]
    },
      {
        name: 'book',
        product: [{
          name: 'dostoyevski',
          model: [
            { id: 'Axe', name: 'Axe' },
            { id: 'accord', name: 'accord' },
            { id: 'crv', name: 'crv' },
            { id: 'pilot', name: 'pilot' },
            { id: 'odyssey', name: 'odyssey' }
          ]
        }]
      }
    ]
  };

  constructor(public fb: FormBuilder) {

    this.editorOptions = new JsonEditorOptions();
    //this.editorOptions.schema = schema;
    this.editorOptions.schema = schema_alarm;



    this.initEditorOptions(this.editorOptions);

    this.editorOptions2 = new JsonEditorOptions();
    this.initEditorOptions(this.editorOptions2)

  }

  ngOnInit() {

/*     this.showData = this.data = {
      'randomNumber': 2,
      'products': [
        {
          'name': 'car',
          'product':
            [
              {
                'name': 'honda',
                'model': [
                  { 'id': 'civic', 'name': 'civic' },
                  { 'id': 'accord', 'name': 'accord' }, { 'id': 'crv', 'name': 'crv' },
                  { 'id': 'pilot', 'name': 'pilot' }, { 'id': 'odyssey', 'name': 'odyssey' }
                ]
              }
            ]
        }
      ]
    }; */

/*     this.showData = this.data = {
      'source': {
        'id': '11111111111'
      },
      'type': 'c8y_LockEvent',
      'text': 'This door is locked!',
      'time': '2022-08-05T00:14:49.389+02:00'
    }; */

    this.showData = this.data = {
      'source': {
        'id': '11111111111'
      },
      'type': 'c8y_LockAlarm',
      'text': 'This door is locked and it is an alrm!',
      'time': '2022-08-05T00:14:49.389+02:00',
      'severity': 'MINOR'
    };

    //     'time': '2022-08-05T00:14:49.389+02:00'

    this.data2 = {
      'nedata': 'test'
    };

    this.form = this.fb.group({
      myinput: [this.data2]
    });

    // this.editorOptions.onChange = this.changeLog.bind(this);
  }

  changeLog(event = null) {
    console.log(event);
    console.log('change:', this.editor);

    /**
     * Manual validation based on the schema
     * if the change does not meet the JSON Schema, it will use the last data
     * and will revert the user change.
     */
    const editorJson = this.editor.getEditor()
    editorJson.validate()
    const errors = editorJson.validateSchema.errors
    if (errors && errors.length > 0) {
      console.log('Errors found', errors)
      editorJson.set(this.showData);
    } else {
      this.showData = this.editor.get();
    }
  }

  changeEvent(event: any) {
    console.log(event);
  }

  initEditorOptions(editorOptions: any) {
    // this.editorOptions.mode = 'code'; // set only one mode
    editorOptions.modes = ['code', 'tree']; // set all allowed modes
    editorOptions.statusBar = true;
    editorOptions.enableSort = false;
    editorOptions.enableTransform = false;
    editorOptions.search = false;
    editorOptions.onEvent =  function(node: any, event: any) {
              if (event.type == "click") {
                // var container = document.getElementById("path");
                var absolute = "";
                for (let i = 0; i < node.path.length; i++) {
                    //console.log("Say hello", typeof node.path[i]);
                    if (typeof node.path[i] === 'number') {
                        absolute = absolute.substring(0, absolute.length - 1);
                        absolute += '[' + node.path[i] + ']';
    
                    } else {
                        absolute += node.path[i];
                    }
                    if (i !== node.path.length - 1) absolute += ".";
                }
                console.log("Path:", absolute);
              }
  }

   editorOptions.onSelectionChange =  function() {
      console.log("Selection changed!");  
    }
  }

  changeObject() {
    this.data.randomNumber = Math.floor(Math.random() * 8);
  }

  changeData() {
    this.data = Object.assign({}, this.data,
      { randomNumber: Math.floor(Math.random() * 8) });
  }

  /**
   * Example on how get the json changed from the jsoneditor
   */
  getData() {
    const changedJson = this.editor.get();
    console.log(changedJson);
  }

  print(v: any) {
    return JSON.stringify(v, null, 2);
  }

  makeOptions = () => {
    return new JsonEditorOptions();
  }

  jsonPathChanged(event: any) {
    let p: string = event.target.value;
    console.log(p);
    const ns = p.split(".");
    const selection = {path: ns};
    this.editor.setSelection(selection, selection)
  }

}
