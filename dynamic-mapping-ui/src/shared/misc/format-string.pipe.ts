import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
  name: 'formatStringAsWords'
})
export class FormatStringPipe implements PipeTransform {
  transform(value: string, removeLastWords: number = 0): string {
    if (!value) return '';

    // First, replace underscores with spaces
    const withSpaces = value.replace(/_/g, ' ');

    // Convert to lowercase and split into words
    const words = withSpaces.toLowerCase().split(' ');

    // Remove last X words if specified
    const trimmedWords = (removeLastWords > 0 && words.length > removeLastWords)
      ? words.slice(0, -removeLastWords)
      : words;

    // Capitalize the first letter of each word
    const capitalizedWords = trimmedWords.map(word =>
      word.charAt(0).toUpperCase() + word.slice(1)
    );

    // Join the words back together
    return capitalizedWords.join(' ');
  }
}