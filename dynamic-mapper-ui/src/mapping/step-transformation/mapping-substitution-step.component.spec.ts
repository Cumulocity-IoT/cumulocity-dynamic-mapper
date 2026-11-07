import '../../shared';
import { MappingSubstitutionStepComponent } from './mapping-substitution-step.component';

describe('MappingSubstitutionStepComponent showExpandArrayHint', () => {
  // The getter only reads substitutionModel, so no TestBed (and no CoreModule DI graph) is needed.
  const hintFor = (substitutionModel: any): boolean => {
    const component = Object.create(MappingSubstitutionStepComponent.prototype);
    component.substitutionModel = substitutionModel;
    return component.showExpandArrayHint;
  };

  it('is shown when the source expression yields an array and expand-as-array is off', () => {
    expect(hintFor({ sourceExpression: { resultType: 'Array' }, expandArray: false })).toBeTrue();
    expect(hintFor({ sourceExpression: { resultType: 'Array' } })).toBeTrue();
  });

  it('is hidden once expand-as-array is on', () => {
    expect(hintFor({ sourceExpression: { resultType: 'Array' }, expandArray: true })).toBeFalse();
  });

  it('is hidden for non-array results and before anything was evaluated', () => {
    expect(hintFor({ sourceExpression: { resultType: 'String' } })).toBeFalse();
    expect(hintFor({})).toBeFalse();
    expect(hintFor(undefined)).toBeFalse();
  });
});
