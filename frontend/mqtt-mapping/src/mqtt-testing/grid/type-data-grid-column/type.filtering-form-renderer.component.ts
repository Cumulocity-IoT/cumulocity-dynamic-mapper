import { Component, Inject } from '@angular/core';
import { FilteringFormRendererContext } from '@c8y/ngx-components';
import { TestingDeviceService, TypeFilteringModel } from '../testing.service';

/**
 * This is the example component for custom filtering form.
 * The form can contain any inputs you want.
 * The important thing is to invoke one of the 2 methods:
 *
 * - `applyFilter` which sets `filterPredicate` or `externalFilterQuery` in the column,
 *   these values will later be used to generate the query
 * - `resetFilter` which resets filter settings in the column
 *
 * Our example shows the list of checkboxes. Selecting them modifies the query being sent.
 */
@Component({
  template: `
    <form #filterForm="ngForm">
      <strong>Show managed objects of type:</strong>
      <c8y-form-group class="m-b-0">
        <label class="c8y-checkbox">
          <input type="checkbox" name="group" [(ngModel)]="model.group" />
          <span></span>
          <span>Group</span>
        </label>
      </c8y-form-group>
      <c8y-form-group class="m-b-0">
        <label class="c8y-checkbox">
          <input type="checkbox" name="device" [(ngModel)]="model.device" />
          <span></span>
          <span>Device</span>
        </label>
      </c8y-form-group>
    </form>

    <div class="data-grid__dropdown__footer d-flex separator-top">
      <button class="btn btn-default btn-sm m-r-8 flex-grow" (click)="resetFilter()">Reset</button>
      <button
        class="btn btn-primary btn-sm flex-grow"
        [disabled]="filterForm.invalid"
        (click)="applyFilter()"
      >
        Apply
      </button>
    </div>
  `
})
export class TypeFilteringFormRendererComponent {
  model: TypeFilteringModel;

  constructor(
    public context: FilteringFormRendererContext,
    @Inject(TestingDeviceService) public service: TestingDeviceService
  ) {
    // restores the settings from current column setup
    this.model = (this.context.property.externalFilterQuery || {}).model || {};
  }

  /**
   * Applies the filter.
   * Sets `externalFilterQuery.model` to restore the same settings the next time the form is displayed.
   * Sets `externalFilterQuery.query` to pass the query object to be included in the final data grid query.
   */
  applyFilter() {
    this.context.applyFilter({
      externalFilterQuery: {
        model: this.model,
        query: this.service.getTypeQuery(this.model)
      }
    });
  }

  /** Restes the filter, just call the method from context. */
  resetFilter() {
    this.context.resetFilter();
  }
}
