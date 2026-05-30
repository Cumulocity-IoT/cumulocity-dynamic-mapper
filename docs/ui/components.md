# UI Component Conventions

## Drawer Components

Components opened via `BottomDrawerService.openDrawer()` **must** declare this on their `@Component` decorator:

```ts
host: { class: 'flex-grow d-col fit-h' }
```

Standard drawer template structure:

```html
<div class="d-col flex-nowrap no-align-items p-48 flex-grow col-md-12 col-md-offset-0 c8y-stepper--no-btns">
  <div class="card card--fullpage d-col flex-grow">
    <div class="card-header separator j-c-center"> ... </div>
    <div class="card-inner-scroll flex-grow"> ... </div>
    <div class="card-footer separator p-24 text-center flex-no-shrink">
      <!-- buttons -->
    </div>
  </div>
</div>
```

Key CSS classes: `d-col` = flex column (**not** `flex-col`, which lacks `display:flex`), `flex-grow`, `fit-h`, `flex-no-shrink`.

## Testing Editor Gotchas

- **Empty payload editor:** ensure `resetTestingModel()` is called after mapping updates.
- **Stale test results:** call `sortObjectKeys()` in `displayTestResult()` before passing the result to the editor.
