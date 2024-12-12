import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
  name: 'formatStringAsWords'
})
export class FormatStringPipe implements PipeTransform {
  transform(value: string): string {
    if (!value) return '';

    // First, replace underscores with spaces
    const withSpaces = value.replace(/_/g, ' ');

    // Convert to lowercase and split into words
    const words = withSpaces.toLowerCase().split(' ');

    // Capitalize the first letter of each word
    const capitalizedWords = words.map(word => 
      word.charAt(0).toUpperCase() + word.slice(1)
    );

    // Join the words back together
    return capitalizedWords.join(' ');
  }
}