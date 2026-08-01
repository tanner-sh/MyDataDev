import { Alert, Input, Typography } from 'antd';

const { Text } = Typography;

export type TypedConfirmationField = {
  expected: string;
  value?: string;
  onChange: (value: string) => void;
  ariaLabel: string;
};

export function TypedConfirmationFields({
  target,
  production,
  autoFocus = 'target'
}: {
  target?: TypedConfirmationField;
  production?: TypedConfirmationField;
  autoFocus?: 'target' | 'production' | 'none';
}) {
  return (
    <div className="typed-confirmation-fields">
      {target && (
        <label className="typed-confirmation-field">
          <Text>输入完整对象名 <Text code>{target.expected}</Text> 以确认操作</Text>
          <Input
            autoFocus={autoFocus === 'target'}
            aria-label={target.ariaLabel}
            value={target.value}
            placeholder={target.expected}
            onChange={(event) => target.onChange(event.target.value)}
          />
        </label>
      )}
      {production && (
        <div>
          <Alert type="error" showIcon title="生产连接保护" description={<>还需输入连接名 <Text code>{production.expected}</Text></>} />
          <label className="typed-confirmation-field">
            <Text>生产连接名</Text>
            <Input
              autoFocus={autoFocus === 'production'}
              aria-label={production.ariaLabel}
              value={production.value}
              placeholder={production.expected}
              onChange={(event) => production.onChange(event.target.value)}
            />
          </label>
        </div>
      )}
    </div>
  );
}
