import { NO_ERRORS_SCHEMA } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AlertService, BottomDrawerRef } from '@c8y/ngx-components';
import { ToolCallPart } from '@c8y/ngx-components/ai';
import { TranslateService } from '@ngx-translate/core';
import { Direction, Mapping, SharedService, TransformationType } from '../../shared';
import { AiAgentService } from '../core/ai-agent.service';
import { AiChatHistoryService, generationInputsKey } from '../core/ai-chat-history.service';
import { MappingService } from '../core/mapping.service';
import { AiPromptComponent } from './ai-prompt.component';

describe('AiPromptComponent — result hand-over', () => {
  let component: AiPromptComponent;
  let mappingService: jasmine.SpyObj<MappingService>;

  function create(transformationType: TransformationType): void {
    TestBed.overrideComponent(AiPromptComponent, {
      set: { imports: [], providers: [], schemas: [NO_ERRORS_SCHEMA], template: '<div></div>' }
    });
    const fixture = TestBed.createComponent(AiPromptComponent);
    component = fixture.componentInstance;
    component.mapping = {
      identifier: 'm1',
      direction: Direction.INBOUND,
      transformationType,
      useExternalId: true
    } as Mapping;
    (component as any).mappingForAI = { sourceTemplate: {}, targetTemplate: {} };
  }

  const toolCall = (toolName: string, input: Record<string, unknown>, extra: Partial<ToolCallPart> = {}): ToolCallPart =>
    ({ type: 'tool-result', toolName, toolCallId: 'c1', input, ...extra }) as ToolCallPart;

  const assistantReply = (text: string) =>
    ({ role: 'assistant', content: [{ type: 'text', text }] }) as any;

  beforeEach(() => {
    mappingService = jasmine.createSpyObj<MappingService>('MappingService', ['evaluateExpression']);
    mappingService.evaluateExpression.and.resolveTo({} as any);
    TestBed.configureTestingModule({
      providers: [
        { provide: AlertService, useValue: jasmine.createSpyObj('AlertService', ['danger', 'warning']) },
        { provide: SharedService, useValue: {} },
        { provide: BottomDrawerRef, useValue: { close: () => undefined } },
        { provide: MappingService, useValue: mappingService },
        { provide: AiAgentService, useValue: {} },
        { provide: TranslateService, useValue: { instant: (k: string) => k } },
        AiChatHistoryService
      ]
    });
  });

  describe('Smart Function', () => {
    beforeEach(() => create(TransformationType.SMART_FUNCTION));

    it('takes the code from the submit-smart-function tool input', async () => {
      await component.onToolResult(
        toolCall('submit-smart-function', { code: 'function onMessage(msg, context) { return null; }' })
      );
      expect(component.valid).toBeTrue();
      expect(component.generatedCode).toContain('function onMessage');
    });

    it('ignores a tool call that has not finished or failed', async () => {
      const input = { code: 'function onMessage(msg, context) {}' };
      await component.onToolResult(toolCall('submit-smart-function', input, { type: 'tool-executing' }));
      await component.onToolResult(toolCall('submit-smart-function', input, { error: true }));
      expect(component.valid).toBeFalse();
    });

    it('ignores the substitutions tool and unrelated tools', async () => {
      await component.onToolResult(toolCall('submit-substitutions', { substitutions: [{ pathSource: 'a', pathTarget: 'b' }] }));
      await component.onToolResult(toolCall('evaluate-jsonata-expression', { expression: 'a' }));
      expect(component.valid).toBeFalse();
      expect(component.substitutions).toEqual([]);
    });

    it('does not let an example code block in the reply text override the submitted code', async () => {
      await component.onToolResult(
        toolCall('submit-smart-function', { code: 'function onMessage(msg, context) { return "final"; }' })
      );
      await component.onMessageFinish(
        assistantReply('For comparison:\n```javascript\nfunction onMessage(msg, context) { return "example"; }\n```')
      );
      expect(component.generatedCode).toContain('final');
    });

    it('still scrapes the reply text when the agent did not use the tool (older agent definitions)', async () => {
      await component.onMessageFinish(
        assistantReply('Done.\n```javascript\nfunction onMessage(msg, context) { return "scraped"; }\n```')
      );
      expect(component.valid).toBeTrue();
      expect(component.generatedCode).toContain('scraped');
    });
  });

  describe('JSONata substitutions', () => {
    beforeEach(() => create(TransformationType.JSONATA));

    it('takes the substitutions from the submit-substitutions tool input and fills in optional fields', async () => {
      await component.onToolResult(
        toolCall('submit-substitutions', {
          substitutions: [
            { pathSource: 'mea.value', pathTarget: 'c8y_Temp.T.value' },
            { pathSource: 'mea.unit', pathTarget: 'c8y_Temp.T.unit', repairStrategy: 'NOT_A_STRATEGY' }
          ]
        })
      );
      expect(component.valid).toBeTrue();
      expect(component.substitutions.length).toBe(2);
      expect(component.substitutions[0].expandArray).toBeFalse();
      expect(component.substitutions[0].repairStrategy).toBe('DEFAULT' as any);
      expect(component.substitutions[1].repairStrategy).toBe('DEFAULT' as any);
    });

    it('rejects a malformed submission instead of marking it valid', async () => {
      await component.onToolResult(toolCall('submit-substitutions', { substitutions: [{ pathSource: '', pathTarget: 'b' }] }));
      await component.onToolResult(toolCall('submit-substitutions', { substitutions: 'nope' }));
      expect(component.valid).toBeFalse();
    });

    it('ignores the Smart Function tool', async () => {
      await component.onToolResult(toolCall('submit-smart-function', { code: 'function onMessage() {}' }));
      expect(component.valid).toBeFalse();
    });

    it('still scrapes the reply text when the agent did not use the tool', async () => {
      await component.onMessageFinish(
        assistantReply('```json\n[{"pathSource":"a","pathTarget":"b","expandArray":false}]\n```')
      );
      expect(component.valid).toBeTrue();
      expect(component.substitutions[0].pathSource).toBe('a');
    });

    it('reads the next reply after a new user message again', async () => {
      await component.onToolResult(toolCall('submit-substitutions', { substitutions: [{ pathSource: 'a', pathTarget: 'b' }] }));
      component.onMessagesChange([{ role: 'user', content: 'again' } as any]);
      await component.onMessageFinish(
        assistantReply('```json\n[{"pathSource":"x","pathTarget":"y","expandArray":false}]\n```')
      );
      expect(component.substitutions[0].pathSource).toBe('x');
    });
  });

  describe('chat history persistence', () => {
    let history: AiChatHistoryService;
    let agentChat: { saveChatHistory: jasmine.Spy };

    beforeEach(() => {
      create(TransformationType.SMART_FUNCTION);
      history = TestBed.inject(AiChatHistoryService);
      agentChat = { saveChatHistory: jasmine.createSpy('saveChatHistory').and.returnValue({ snapshot: 1 }) };
      (component as any).agentChat = agentChat;
      (component as any).inputsKey = generationInputsKey((component as any).mappingForAI);
    });

    it('stores the session once the chat has messages', () => {
      component.onMessagesChange([{ role: 'user', content: 'hi' } as any]);
      expect((history.get('m1') as any)?.history).toEqual({ snapshot: 1 });
    });

    it('does not store an untouched chat', () => {
      component.persistSession();
      expect(history.get('m1')).toBeUndefined();
    });

    it('ignores the empty message list the chat emits while initialising, so a stored session survives', () => {
      component.onMessagesChange([{ role: 'user', content: 'hi' } as any]);
      component.onMessagesChange([]);
      expect(history.get('m1')).toBeDefined();
      component.persistSession(); // hasMessages must still be true
      expect(agentChat.saveChatHistory).toHaveBeenCalledTimes(2);
    });

    it('stores the session on cancel and on save', () => {
      component.onMessagesChange([{ role: 'user', content: 'hi' } as any]);
      history.delete('m1');
      component.cancel();
      expect(history.get('m1')).toBeDefined();
      history.delete('m1');
      component.save();
      expect(history.get('m1')).toBeDefined();
    });

    it('does not rewrite the stored session when the component is destroyed', () => {
      component.onMessagesChange([{ role: 'user', content: 'hi' } as any]);
      agentChat.saveChatHistory.calls.reset();
      TestBed.resetTestingModule(); // destroys the component
      expect(agentChat.saveChatHistory).not.toHaveBeenCalled();
    });
  });
});
