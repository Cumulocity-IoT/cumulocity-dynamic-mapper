import {
    AfterContentInit,
    AfterViewInit,
    Component,
    ContentChild,
    ContentChildren,
    ElementRef,
    EventEmitter,
    Input,
    OnChanges,
    OnDestroy,
    Output,
    QueryList,
    SimpleChanges,
    ViewChild,
    ViewChildren,
    forwardRef
} from '@angular/core';
import {
    AbstractControl,
    ControlValueAccessor,
    NG_VALIDATORS,
    NG_VALUE_ACCESSOR,
    ValidationErrors,
    Validator
} from '@angular/forms';
import { BsDropdownDirective, BsDropdownModule } from 'ngx-bootstrap/dropdown';
import { Subject } from 'rxjs';
import { NgClass, NgTemplateOutlet } from '@angular/common';
import { gettext } from '@c8y/ngx-components/gettext';
import { isEqual } from 'lodash-es';
import { C8yTranslatePipe, IconDirective, ListGroupComponent, ListItemBodyComponent, ListItemCheckboxComponent, ListItemComponent, RequiredInputPlaceholderDirective, SelectableItem, SelectableItemTemplate, SelectedItemsDirective, SelectItemDirective, SelectKeyboardService } from '@c8y/ngx-components';

@Component({
    selector: 'd11r-select',
    templateUrl: './custom-select.component.html',
    host: { class: 'c8y-select-v2' },
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            multi: true,
            useExisting: forwardRef(() => CustomSelectComponent)
        },
        {
            provide: NG_VALIDATORS,
            useExisting: forwardRef(() => CustomSelectComponent),
            multi: true
        },
        SelectKeyboardService
    ],
    standalone: true,
    imports: [
    BsDropdownModule,
    NgClass,
    NgTemplateOutlet,
    IconDirective,
    RequiredInputPlaceholderDirective,
    ListGroupComponent,
    ListItemComponent,
    ListItemCheckboxComponent,
    ListItemBodyComponent,
    C8yTranslatePipe
]
})
export class CustomSelectComponent
    implements AfterContentInit, OnChanges, OnDestroy, AfterViewInit, ControlValueAccessor, Validator {
    /**
     * Placeholder text to be displayed in the select.
     */
    @Input() placeholder: string = gettext('Select item…');

    /**
     * Items to be displayed in the select.
     * Can be an array of strings or an array of objects with `label` and `value` properties.
     *
     * @example
     * ```html
     * <c8y-select [items]="[{ label: 'Item 1', value: 'item1' }, { label: 'Item 2', value: 'item2' }]"></c8y-select>
     * ```
     *
     * @example
     * ```html
     * <c8y-select [items]="['Item 1', 'Item 2', 'Item 3']"></c8y-select>
     * ```
     *
     * For more complex scenarios, you can use content-projection:
     *
     * @example
     * ```html
     * <c8y-select>
     *    <i [c8yIcon]="'rocket'" class="text-16" *c8ySelectItem="'rocket'; label: 'Rocket'"></i>
     *    <i [c8yIcon]="'car'" class="text-16" *c8ySelectItem="'car'; label: 'Car'"></i>
     * </c8y-select>
     * ```
     */
    @Input() set items(value: string[] | SelectableItem[] | SelectableItemTemplate[]) {
        this._items = value.map(item => {
            if (typeof item === 'string') {
                return { label: item, value: item };
            }
            return item;
        });
    }

    /**
     * The items to be displayed in the select.
     */
    get items(): SelectableItemTemplate[] {
        return this._items;
    }

    /**
     * The selected item.
     */
    @Input()
    set selected(value: string | SelectableItem | Array<string | SelectableItem>) {
        const ensuredArray: (string | SelectableItem)[] = Array.isArray(value) ? value : [value];
        const normalizedArray = ensuredArray.map(item => {
            if (typeof item === 'string') {
                return { label: item, value: item };
            }
            return item;
        });
        this._selected = normalizedArray
            .map(item => {
                return this._items.find(i => i.value === item.value);
            })
            .filter(Boolean);
    }

    /**
     * Returns the selected item.
     */
    get selected(): SelectableItem[] {
        return this._selected;
    }

    /**
     * The container to put the dropdown to. Defaults to body.
     */
    @Input()
    container: '' | 'body' = 'body';

    /**
     * If set to true, the user can select multiple items.
     */
    @Input()
    multi = false;

    /**
     * If enabled, an item can be selected with the space key.
     */
    @Input() canSelectWithSpace = !this.multi;

    /**
     * If set to true, the select is disabled.
     */
    @Input()
    disabled = false;

    /**
     * Defines, if the dropdown should close automatically after user interaction.
     */
    @Input()
    autoClose = true;

    /**
     * Defines if the dropdown should stay open when the user clicks inside the select.
     * If set to true, the dropdown will only close when the user clicks outside the select.
     */
    @Input()
    insideClick: boolean;

    /**
     * Marks the select as required.
     */
    @Input()
    required = false;

    /**
     * Allows the user to clear the selection.
     */
    @Input()
    canDeselect = false;

    /**
     * The name used for this select.
     */
    @Input()
    name = 'select';

    /**
     * The icon to be displayed in the select.
     */
    @Input()
    icon = 'caret-down';

    /**
     * Emits if a item is selected.
     */
    @Output()
    onSelect = new EventEmitter<SelectableItem>();

    /**
     * Emits if a item was deselected.
     */
    @Output()
    onDeselect = new EventEmitter<SelectableItem>();

    /**
     * Emits when the select icon is clicked.
     */
    @Output()
    onIconClick = new EventEmitter<{ icon: string; $event: MouseEvent }>();

    /**
     * Indicates if the search input has focus.
     */
    searchHasFocus = false;

    /**
     * The selectable items when content projection is used.
     * @ignore
     */
    @ContentChildren(SelectItemDirective) projectedSelectableItems: QueryList<SelectItemDirective>;

    /**
     * The selected items when content projection is used.
     * @ignore
     */
    @ContentChild(SelectedItemsDirective) projectedSelectedItems: SelectedItemsDirective;

    @ViewChild('searchControl', { static: false }) private searchControl: ElementRef;
    @ViewChild('dropdown', { static: false }) private dropdown: BsDropdownDirective;
    @ViewChildren(ListItemComponent) private list: QueryList<ListItemComponent>;

    /**
     * A item which is preselected. It is used when a user types in the search input to give a visual typeahead feedback.
     */
    get preselectedItem(): SelectableItem {
        return this._preselectedItem;
    }

    /**
     * The internal select element.
     * @ignore
     */
    private _selected: SelectableItem[] = [];

    /**
     * The internal pre-select element. It is used when a user types in the search input to give a visual typeahead feedback.
     * @ignore
     */
    private _preselectedItem: SelectableItem;

    /**
     * The internal items element.
     * @ignore
     */
    private _items: SelectableItemTemplate[] = [];

    private destroy$ = new Subject<void>();
    private onChange: (items: SelectableItem | SelectableItem[]) => void;
    private onTouched: () => void;

    /**
     * @ignore
     * @param selectKeyboardService The service to handle keyboard navigation.
     */
    constructor(private selectKeyboardService: SelectKeyboardService) {
        this.selectKeyboardService.options = {
            emptyInput: true,
            keyboardSearch: true,
            spaceSelect: this.canSelectWithSpace,
            noMatchHighlightFirst: false
        };
    }

    /**
     * @ignore
     */
    ngAfterContentInit(): void {
        if (this.projectedSelectableItems.length > 0) {
            this.projectedSelectableItems.forEach(item => {
                this._items.push({
                    label: item.label,
                    value: item.value,
                    template: item.templateRef
                });
            });
        }

        if (!this.insideClick) {
            this.insideClick = this.multi;
        }
    }

    /**
     * @ignore
     */
    ngAfterViewInit(): void {
        this.selectKeyboardService
            .register$(this.searchControl.nativeElement, this.list, this.dropdown)
            .subscribe(selectedIndex => {
                if (selectedIndex > -1) {
                    this._preselectedItem = this._items[selectedIndex];
                } else {
                    this._preselectedItem = null;
                }
            });
    }

    /**
     * @ignore
     */
    ngOnChanges(changes: SimpleChanges): void {
        if (changes['canSelectWithSpace']) {
            this.selectKeyboardService.options = {
                emptyInput: true,
                keyboardSearch: true,
                spaceSelect: changes['canSelectWithSpace'].currentValue,
                noMatchHighlightFirst: false
            };
        }
    }

    /**
     * @ignore
     */
    ngOnDestroy(): void {
        this.destroy$.next();
        this.destroy$.complete();
        this.selectKeyboardService.unregister();
    }

    /**
     * Selects an item. In the multi mode, it will toggle the selection of the item.
     * @param item The item to select.
     */
    select(item: SelectableItem): void {
        if (this.multi) {
            const isSelected = this._selected.indexOf(item) > -1;
            if (isSelected) {
                this.deselect(item);
                return;
            }
            this._selected.push(item);
            this.emitChangeEvent();
            this.onSelect.emit(item);
            return;
        }
        this._selected = [item];
        this._preselectedItem = item;
        this.emitChangeEvent();
        this.onSelect.emit(item);
    }

    /**
     * Deselects an item.
     * @param item The item to deselect.
     */
    deselect(item: SelectableItem): void {
        let index = this._selected.indexOf(item);
        if (index === -1) {
            index = this._selected.findIndex(i => isEqual(i, item));
        }
        if (index > -1) {
            this._selected.splice(index, 1);
            this.emitChangeEvent();
            this.onDeselect.emit(item);
            this._preselectedItem = null;
        }
    }

    /**
     * Deselects all items
     */
    deselectAll(): void {
        if (this._selected.length > 0) {
            this.onDeselect.emit();
            this._selected = [];
            this._preselectedItem = null;
            this.searchControl.nativeElement.value = '';
            this.close();
            this.emitChangeEvent();
        }
    }

    /**
     * Closes the dropdown.
     */
    close(): void {
        this.dropdown.hide();
    }

    /**
     * Opens the dropdown.
     */
    open(): void {
        this.dropdown.show();
    }

    /**
     * @ignore
     * @param value The value to write.
     */
    writeValue(value: SelectableItem | SelectableItem[]) {
        if (value) {
            this.selected = value;
        }
    }

    /**
     * @ignore
     * @param fn The function to register for onChange.
     */
    registerOnChange(fn: (items: SelectableItem | SelectableItem[]) => void): void {
        this.onChange = fn;
    }

    /**
     * @ignore
     * @param fn The function to register for onTouched.
     */
    registerOnTouched(fn: () => void): void {
        this.onTouched = fn;
    }

    /**
     * @ignore
     * @param isDisabled Should disable or not
     */
    setDisabledState(isDisabled: boolean): void {
        this.disabled = isDisabled;
    }

    /**
     * @ignore
     */
    doBlur(): void {
        this.searchHasFocus = false;
        if (this.onTouched) {
            this.onTouched();
        }
    }

    /**
     * @ignore
     */
    doFocus(): void {
        this.open();
        queueMicrotask(() => {
            this.searchHasFocus = true;
        });
    }

    /**
     * @ignore
     */
    validate(control: AbstractControl): ValidationErrors {
        if (this.required && (!control.value || control.value.length === 0)) {
            return { required: true };
        }
        return null;
    }

    /**
     * Triggered if the dropdown was shown.
     * @ignore
     */
    onShown(): void {
        this.searchControl.nativeElement.focus();
    }

    /**
     * Triggered if the dropdown was hidden.
     * @ignore
     */
    onHidden(): void {
        this.searchControl.nativeElement.value = '';
        this._preselectedItem = null;
    }

    private emitChangeEvent(): void {
        if (typeof this.onChange === 'function') {
            this.onChange(this.multi ? this._selected : this._selected[0]);
        }
    }
}