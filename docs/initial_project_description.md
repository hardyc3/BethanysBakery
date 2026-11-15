Project description
Bethany is running a bakery in home. 
She bakes a few days a week but not every day. Each week the items she sells changes. Currently she'll create an order form and send it out to her customers by email or text, they pick what they want to buy and venmo her. The form is a google doc, she'll collect responses and knows how much of each thing to bake. She would rather have the ability to set a max number of items she'll bake and have customers notified that the item is sold out instead of having to text them individually to let them know when something is sold out. Each week she'll create a new form with a picture of the items she'll be baking and the cost per item.  (Spec mapping: live in-app availability/sold-out state and UI messaging satisfy this; see architecture/S4/W3/W4.) 
This project will replace the order forms. There will be a server side API and database that will store the menu for the week, the cost of the items and the total number of each item that can be made on a particular day and then will track orders that come in. There will be an app she can use on her phone to upload new pictures, create new weekly menus with the items that are available on a particular day and how many of each she can make on that day. She will also be able to set pick up times for customers to get the items. There will be a customer app where the customers will be able to pick a pickup time when they place an order. They can view the weekly menu and place orders for specific days, they will be told the total and given a venmo to send paymen to. There will be notifications for the customers so they know their items are ready. There will be notifications for when a new menu is uploaded and for the day before baking day so that they know they need to complete their order if they want something. 


Server
    - Docker container
    - support https 
    - follows security best practices
    - Database for tracking menus, orders, prices, notifications, customer information
    - Server API for handling Admin requests, this includes creating menus, setting which days items are available, pick up times, prices, sending notifications about delays or order completion, marking orders as paid for
    - Server API for handling user requests, this includes creating an order, verifing that the items aren't already sold out, showing payment status
        - User API can only be accessed by users within the USA
        - User API can only be accessed by the User Client app
    - use mailgun or textbee for notifications

User Client
    - has to be a web app so android and apple users can use it
    - requests should be through https
    - follow secure coding practices
    - View specific dates and items available for the date with how many are left or sold out
    - create an order for an item or items
    - allow for customer to select customizations if the item has any
    - show the total that should be sent to Bethany's Venmo
    - show when the order is marked as ready
    - show when the order is marked as paid for
    - notify the customer that a new menu is ready
    - notify the customer that an order is complete and provide a link to a maps pin
    - notify the customer that an order is delayed
    - allow the customer to set an email address, phone number and name
    - each item should contain a link to view an ingredient list
    

Admin Client
    - android app with ability to set a ssl cert or API key so that only approved instances of the app can make requests to the server
    - requests should be through https
    - follow secure coding practices 
    - create a menu for a specific date
        - allow the admin to pick whether or not a notification will be sent, default yes
        - allow the admin to pick a date for the notification to be sent, default 4 days before first baking day in the week of the menu
    - add items to the menu with prices and total available quantity and multiple pickup windows at or after a specific time for each item.
         - allow admin to limit the amount of orders allowed for each pick-up window
        - each item should have a list of ingredients
    - view total orders for a specific date, with paid/unpaid status
    - send notifications that an order is complete
    - send notifications that an order is delayed
    - send notifications that a menu or date is being cancelled with optional reason entered by the admin 
    - recieve notification when orders are made
    - allow admin to mark an order as accepted or rejected
        - send notification on rejection with reason
    - allow admin to mark and order as paid for
    - allow admin to see a list of what needs to be baked on a chosen date
    - allow admin to provide optional costumizations to items. For exapmle, if I am selling bagels, I want my costumers to be able to choose from a list I create which toppings they would like. 