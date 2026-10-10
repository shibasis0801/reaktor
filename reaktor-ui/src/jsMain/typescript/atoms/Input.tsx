/**
 * Atom: Input
 *
 * Text input field for user data entry.
 * Supports various input types, validation states, and accessories.
 */

import React, { useState } from 'react';
import { useTokens } from '../theme/ReaktorThemeProvider';
import { ComponentSize, ComponentState } from './Button';

// ============================================================================
// TYPES
// ============================================================================

export type InputVariant = 'outlined' | 'filled' | 'underlined';
export type InputType = 'text' | 'password' | 'email' | 'number' | 'phone' | 'url' | 'search' | 'multiline';

export interface RInputProps extends Omit<React.InputHTMLAttributes<HTMLInputElement | HTMLTextAreaElement>, 'size'> {
  value?: string;
  onValueChange?: (value: string) => void;
  placeholder?: string;
  label?: string;
  helperText?: string;
  errorText?: string;
  leadingIcon?: React.ReactNode;
  trailingIcon?: React.ReactNode;
  onTrailingIconClick?: () => void;
  size?: ComponentSize;
  variant?: InputVariant;
  state?: ComponentState;
  inputType?: InputType;
  maxLines?: number;
  maxLength?: number;
}

// ============================================================================
// MAIN COMPONENT
// ============================================================================

export function RInput({
  value = '',
  onValueChange,
  onChange,
  placeholder = '',
  label,
  helperText,
  errorText,
  leadingIcon,
  trailingIcon,
  onTrailingIconClick,
  size = 'medium',
  variant = 'outlined',
  state = 'enabled',
  inputType = 'text',
  maxLines = 1,
  maxLength,
  style,
  ...props
}: RInputProps) {
  const tokens = useTokens();
  const { colors, shapes, spacing, sizing } = tokens;
  const [focused, setFocused] = useState(false);

  const isError = state === 'error' || !!errorText;
  const isDisabled = state === 'disabled';
  const isMultiline = inputType === 'multiline';

  const height = isMultiline ? 'auto' : {
    small: sizing.inputSm,
    medium: sizing.inputMd,
    large: sizing.inputLg,
  }[size];

  const getBorderColor = () => {
    if (isError) return colors.error;
    if (focused) return colors.primary;
    return colors.outline;
  };

  const containerStyle: React.CSSProperties = {
    display: 'flex',
    flexDirection: 'column',
    gap: spacing.xs,
    opacity: isDisabled ? 0.5 : 1,
  };

  const inputContainerStyle: React.CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    gap: spacing.sm,
    height,
    minHeight: isMultiline ? sizing.inputLg : undefined,
    padding: `0 ${spacing.md}`,
    backgroundColor: variant === 'filled' ? colors.surfaceContainerHigh : 'transparent',
    border: variant === 'outlined' ? `1px solid ${getBorderColor()}` : 'none',
    borderBottom: variant === 'underlined' ? `2px solid ${getBorderColor()}` : undefined,
    borderRadius: variant === 'outlined' ? shapes.sm : 0,
    transition: 'border-color 150ms ease-in-out',
  };

  const inputStyle: React.CSSProperties = {
    flex: 1,
    height: '100%',
    padding: isMultiline ? spacing.sm : 0,
    border: 'none',
    outline: 'none',
    backgroundColor: 'transparent',
    color: colors.onSurface,
    fontSize: 'inherit',
    fontFamily: 'inherit',
    resize: isMultiline ? 'vertical' : 'none',
    ...style,
  };

  const labelStyle: React.CSSProperties = {
    color: isError ? colors.error : focused ? colors.primary : colors.onSurfaceVariant,
    fontSize: '12px',
    fontWeight: 500,
    transition: 'color 150ms ease-in-out',
  };

  const helperStyle: React.CSSProperties = {
    color: isError ? colors.error : colors.onSurfaceVariant,
    fontSize: '12px',
  };

  const iconStyle: React.CSSProperties = {
    width: sizing.iconMd,
    height: sizing.iconMd,
    color: colors.onSurfaceVariant,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
  };

  const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
    let newValue = e.target.value;
    if (maxLength) {
      newValue = newValue.slice(0, maxLength);
    }
    onValueChange?.(newValue);
    onChange?.(e as React.ChangeEvent<HTMLInputElement>);
  };

  const getInputType = () => {
    switch (inputType) {
      case 'password': return 'password';
      case 'email': return 'email';
      case 'number': return 'number';
      case 'phone': return 'tel';
      case 'url': return 'url';
      case 'search': return 'search';
      default: return 'text';
    }
  };

  const InputElement = isMultiline ? 'textarea' : 'input';

  return (
    <div style={containerStyle}>
      {label && <label style={labelStyle}>{label}</label>}
      <div style={inputContainerStyle}>
        {leadingIcon && <span style={iconStyle}>{leadingIcon}</span>}
        <InputElement
          {...(props as any)}
          value={value}
          onChange={handleChange}
          placeholder={placeholder}
          disabled={isDisabled}
          type={isMultiline ? undefined : getInputType()}
          rows={isMultiline ? maxLines : undefined}
          style={inputStyle}
          onFocus={(e) => {
            setFocused(true);
            props.onFocus?.(e as any);
          }}
          onBlur={(e) => {
            setFocused(false);
            props.onBlur?.(e as any);
          }}
        />
        {trailingIcon && (
          <button
            type="button"
            onClick={onTrailingIconClick}
            style={{
              ...iconStyle,
              background: 'none',
              border: 'none',
              padding: 0,
              cursor: onTrailingIconClick ? 'pointer' : 'default',
            }}
          >
            {trailingIcon}
          </button>
        )}
      </div>
      {(errorText || helperText) && (
        <span style={helperStyle}>{errorText || helperText}</span>
      )}
    </div>
  );
}

// ============================================================================
// CONVENIENCE COMPONENTS
// ============================================================================

export function RSearchInput(props: Omit<RInputProps, 'inputType'>) {
  return <RInput {...props} inputType="search" />;
}

export function RPasswordInput(props: Omit<RInputProps, 'inputType'>) {
  return <RInput {...props} inputType="password" />;
}

export function REmailInput(props: Omit<RInputProps, 'inputType'>) {
  return <RInput {...props} inputType="email" />;
}

export function RTextArea(props: Omit<RInputProps, 'inputType'>) {
  return <RInput {...props} inputType="multiline" maxLines={props.maxLines || 5} />;
}
