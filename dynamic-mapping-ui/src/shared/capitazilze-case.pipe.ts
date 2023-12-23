// camel-case.pipe.ts
import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
  name: 'capitalCase'
})
export class CapitalizeCasePipe implements PipeTransform {
  transform(value: string): string {
    if (!value) {
      return value;
    }

    // Split the string into an array of words
    const words = value.split(' ');

    // Convert each word to lowercase and capitalize the first letter
    const camelCaseWords = words.map((word, index) => {
      return word.charAt(0).toUpperCase() + word.slice(1).toLowerCase();
    });

    // Join the words back into a single string
    const camelCaseString = camelCaseWords.join('');

    return camelCaseString;
  }
}
